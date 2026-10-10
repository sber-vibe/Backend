package org.example.subsidy;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private final CheckSettings settings;
    private final long processingTimeoutSeconds;
    private final ExecutorService modelWorkers = Executors.newFixedThreadPool(2, task -> {
        var thread = new Thread(task, "document-model-worker"); thread.setDaemon(true); return thread;
    });
    private long storedBytes;

    @Autowired
    public ApplicationService(DocumentRecognizer recognizer, CheckSettings settings,
                              @Value("${document.processing-timeout-seconds:240}") long timeout) {
        this(recognizer, Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "application-document-processing");
            thread.setDaemon(true);
            return thread;
        }), settings, timeout);
    }

    ApplicationService(DocumentRecognizer recognizer, Executor processingQueue) {
        this(recognizer, processingQueue, new CheckSettings(), 240);
    }
    ApplicationService(DocumentRecognizer recognizer) { this(recognizer, new CheckSettings(), 240); }

    ApplicationService(DocumentRecognizer recognizer, Executor processingQueue, CheckSettings settings, long timeout) {
        this.recognizer = recognizer;
        this.processingQueue = processingQueue;
        this.settings = settings;
        this.processingTimeoutSeconds = Math.max(1, Math.min(600, timeout));
    }

    @PreDestroy
    public void stop() {
        if (processingQueue instanceof ExecutorService executor) executor.shutdownNow();
        modelWorkers.shutdownNow();
    }

    public List<DocumentType> requiredTypes() { return List.copyOf(REQUIRED); }
    public List<DocumentType> documentTypes() { return Arrays.stream(DocumentType.values()).filter(type -> type != DocumentType.UNKNOWN).toList(); }
    public List<Program> programs() {
        return settings.programs(true);
    }

    public synchronized ApplicationView create(CreateApplication request) {
        if (applications.size() >= 100) throw error(HttpStatus.CONFLICT, "Достигнут лимит: 100 заявок");
        String code = blankToNull(request.programCode());
        if (code != null && (settings.program(code) == null || !settings.program(code).active())) throw error(HttpStatus.BAD_REQUEST, "Выберите действующую программу субсидии");
        var state = new ApplicationState(UUID.randomUUID(), code, Instant.now());
        applications.put(state.id, state);
        journal(state, null, "APPLICATION_CREATED", "Создана заявка", "Система", "SYSTEM", null, null);
        return view(state);
    }

    public synchronized List<ApplicationView> list() {
        return applications.values().stream().map(this::view).sorted(Comparator.comparing(ApplicationView::createdAt).reversed()).toList();
    }
    public synchronized ApplicationView get(UUID id) { return view(require(id)); }
    public synchronized DocumentView document(UUID id, UUID documentId) { return requireDocument(require(id), documentId).view(); }
    public synchronized List<JournalEntry> journal(UUID id) { return List.copyOf(require(id).journal); }
    public synchronized List<JournalEntry> allJournal(int limit) {
        return applications.values().stream().flatMap(state -> state.journal.stream())
                .sorted(Comparator.comparing(JournalEntry::at).reversed()).limit(Math.max(1, Math.min(limit, 1000))).toList();
    }

    public synchronized ApplicationView decide(UUID id, DecisionRequest request, String actor) {
        var state = require(id);
        String comment = blankToNull(request.comment());
        if (request.decision() != DecisionCode.ACCEPTED && comment == null) throw error(HttpStatus.BAD_REQUEST, "Для возврата или отклонения укажите комментарий");
        String specialist = blankToNull(actor) == null ? "Специалист" : actor;
        state.decision = new Decision(request.decision(), comment, specialist, Instant.now());
        journal(state, null, "DECISION_MADE", "Сохранено решение специалиста", specialist, "SPECIALIST", request.decision().name(), comment);
        return view(state);
    }

    public synchronized DocumentView upload(UUID id, DocumentType type, MultipartFile file) throws IOException {
        var state = require(id);
        if (state.documents.size() >= 20) throw error(HttpStatus.CONFLICT, "Достигнут лимит: 20 документов в заявке");
        if (file.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "Файл пустой");
        if (file.getSize() > MAX_FILE_SIZE || storedBytes + file.getSize() > MAX_TOTAL_SIZE) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "Превышен лимит: 50 МБ на файл, 50 МБ на все документы");
        String filename = file.getOriginalFilename();
        if (filename == null || filename.length() > 255 || filename.contains("/") || filename.contains("\\") || filename.chars().anyMatch(Character::isISOControl)) throw error(HttpStatus.BAD_REQUEST, "Недопустимое имя файла");
        if (!filename.toLowerCase(Locale.ROOT).matches(".+\\.(pdf|doc|docx|xls|xlsx|txt|png|jpg|jpeg)$")) throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Допустимые форматы: PDF, DOC, DOCX, XLS, XLSX, TXT, PNG, JPG, JPEG");
        byte[] content = file.getBytes();
        if (content.length > MAX_FILE_SIZE || storedBytes + content.length > MAX_TOTAL_SIZE) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "Превышен лимит: 50 МБ на файл, 50 МБ на все документы");
        var document = new StoredDocument(UUID.randomUUID(), type, filename, content, Instant.now());
        state.documents.put(document.id, document);
        storedBytes += content.length;
        invalidateReport(state);
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
            var job = modelWorkers.submit(() -> recognizer.recognize(content, filename));
            DocumentRecognizer.Recognition result;
            try { result = job.get(processingTimeoutSeconds, TimeUnit.SECONDS); }
            catch (TimeoutException exception) { job.cancel(true); throw error(HttpStatus.GATEWAY_TIMEOUT, "Превышено время обработки документа"); }
            catch (InterruptedException exception) { job.cancel(true); throw exception; }
            catch (ExecutionException exception) {
                if (exception.getCause() instanceof Exception cause) throw cause;
                throw new IllegalStateException("Document processing failed", exception.getCause());
            }
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
        if (pending) state.report = buildReport(state);
        else invalidateReport(state);
    }

    private static void invalidateReport(ApplicationState state) {
        state.report = null;
        state.documents.values().forEach(document -> document.outcome = null);
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
        invalidateReport(state);
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
        invalidateReport(state);
        return document.view();
    }

    public synchronized DocumentView reprocess(UUID id, UUID documentId) {
        var state = require(id);
        var document = requireDocument(state, documentId);
        if (!"FAILED".equals(document.processingStatus)) throw error(HttpStatus.CONFLICT, "Повторная обработка доступна только после ошибки");
        document.processingStatus = "UPLOADED";
        document.processingError = null;
        invalidateReport(state);
        journal(state, documentId, "DOCUMENT_REPROCESS_REQUESTED", "Запрошена повторная обработка документа", "Система", "SYSTEM", "UPLOADED", null);
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
        var card = view(state);
        var report = CheckEngine.check(settings.program(card.programCode()), card.documents(), settings.snapshot());
        for (var document : state.documents.values()) {
            if (!"DONE".equals(document.processingStatus) || document.type == null || document.type == DocumentType.UNKNOWN) {
                document.outcome = "FAILED".equals(document.processingStatus) ? "ERRORS" : "ATTENTION";
                continue;
            }
            if ("PROCESSING".equals(report.status())) { document.outcome = null; continue; }
            var related = report.findings().stream().filter(finding -> document.id.equals(finding.documentId())
                    || (finding.documentId() == null && (finding.documentType() == null || finding.documentType() == document.type))).toList();
            document.outcome = related.stream().anyMatch(finding -> "ERROR".equals(finding.severity())) ? "ERRORS"
                    : related.stream().anyMatch(finding -> "WARNING".equals(finding.severity())) ? "ATTENTION" : "PASSED";
        }
        return report;
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
        String programName = settings.program(program) == null ? null : settings.program(program).name();
        if (programName == null && detected) programName = text(application, "program_name");
        return new ApplicationView(state.id, text(application, "company_name"), text(application, "inn"), text(application, "project_name"), number(application, "requested_amount"), state.createdAt,
                state.documents.values().stream().map(StoredDocument::view).toList(), state.report, program, programName, detected, requisites, state.decision);
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
        String typeSource, typeChangedBy, processingStatus = "UPLOADED", processingError, outcome;
        Instant typeChangedAt;
        long revision;
        final Map<String, ExtractedField> fields = new LinkedHashMap<>();
        StoredDocument(UUID id, DocumentType type, String filename, byte[] content, Instant uploadedAt) {
            this.id = id; this.type = type; this.filename = filename; this.content = content; this.uploadedAt = uploadedAt;
            if (type != null) typeSource = "SPECIALIST";
        }
        boolean processing() { return !Set.of("DONE", "FAILED").contains(processingStatus); }
        DocumentView view() { return new DocumentView(id, type, filename, content.length, uploadedAt, processingStatus, processingError, detectedType, confidence, typeSource, typeChangedBy, typeChangedAt, List.copyOf(fields.values()), outcome); }
    }
    private static final class ApplicationState {
        final UUID id;
        final String programCode;
        final Instant createdAt;
        final Map<UUID, StoredDocument> documents = new LinkedHashMap<>();
        final List<JournalEntry> journal = new ArrayList<>();
        CheckReport report;
        Decision decision;
        ApplicationState(UUID id, String programCode, Instant createdAt) { this.id = id; this.programCode = programCode; this.createdAt = createdAt; }
    }
}
