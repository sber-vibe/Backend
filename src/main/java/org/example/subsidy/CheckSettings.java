package org.example.subsidy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import static org.example.subsidy.Models.*;

/** Versioned settings and audit records committed together in the database. */
@Service
public class CheckSettings {
    private final Map<String, Program> programs = new LinkedHashMap<>();
    private final Map<String, Rule> rules = new LinkedHashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();
    private final StateStore store;
    public record Snapshot(Map<String, Program> programs, Map<String, Rule> rules, List<JournalEntry> journal) { }
    public static final List<ApplicationAttribute> ATTRIBUTES = List.of(
            new ApplicationAttribute("requiresCertification", "Продукция подлежит сертификации"),
            new ApplicationAttribute("usesWaterArea", "Используется акватория"));

    public CheckSettings() { this(null); }
    @Autowired public CheckSettings(StateStore store) {
        this.store = store;
        var loaded = store == null ? null : store.read("settings", Snapshot.class);
        if (loaded != null) { programs.putAll(loaded.programs()); rules.putAll(loaded.rules()); journal.addAll(loaded.journal()); return; }
        initialProgram("INNOVATION", "Инновационный проект", List.of(DocumentType.APPLICATION, DocumentType.PD_CONSENT,
                DocumentType.PROJECT_PRESENTATION, DocumentType.COST_JUSTIFICATION, DocumentType.BANK_STATEMENT, DocumentType.GUARANTEE_LETTER), DocumentType.CONFORMITY_CERTIFICATE, "requiresCertification", "если продукция подлежит сертификации");
        initialProgram("BEACH", "Обустройство пляжа", List.of(DocumentType.APPLICATION, DocumentType.PD_CONSENT,
                DocumentType.COST_JUSTIFICATION, DocumentType.BANK_STATEMENT, DocumentType.REAL_ESTATE_DOCUMENT), DocumentType.GIMS_DECLARATION, "usesWaterArea", "если используется акватория");
        initialProgram("MODERNIZATION", "Модернизация производства", List.of(DocumentType.APPLICATION, DocumentType.PD_CONSENT,
                DocumentType.COST_JUSTIFICATION, DocumentType.BANK_STATEMENT, DocumentType.GUARANTEE_LETTER, DocumentType.REAL_ESTATE_DOCUMENT), DocumentType.CONFORMITY_CERTIFICATE, "requiresCertification", "если продукция подлежит сертификации");
        add("MISSING_DOCUMENT", "Обязательные документы", "ERROR", Map.of());
        add("DUPLICATE_DOCUMENT", "Повторные документы", "WARNING", Map.of());
        add("LOW_CONFIDENCE", "Уверенность распознавания", "WARNING", Map.of("threshold", 0.8));
        add("AMOUNT_NOT_RECOGNIZED", "Наличие суммы субсидии", "WARNING", Map.of());
        add("INVALID_AMOUNT", "Положительная сумма субсидии", "ERROR", Map.of());
        add("EXPENSES_SUM", "Арифметика затрат", "ERROR", Map.of("tolerance", 0));
        add("OWN_FUNDS_SHARE", "Доля собственных средств", "ERROR", Map.of("minOwnFundsShare", 0.5));
        add("AMOUNTS_MATCH", "Совпадение сумм документов", "ERROR", Map.of("tolerance", 0));
        add("DOCUMENT_EXPIRY", "Срок действия документа", "WARNING", Map.of("daysBeforeExpiry", 30));
        add("MANUAL_REVIEW_REQUIRED", "Проверка подписей и регламента специалистом", "WARNING", Map.of());
        persist(programs, rules, journal);
    }

    private void initialProgram(String code, String name, List<DocumentType> required, DocumentType conditional, String attribute, String condition) {
        var documents = new ArrayList<>(required.stream().map(type -> new ProgramDocument(type, "REQUIRED")).toList());
        documents.add(new ProgramDocument(conditional, "CONDITIONAL", condition, attribute));
        programs.put(code, new Program(code, name, true, List.copyOf(documents), null, 1L, "Система", Instant.now()));
    }

    private void add(String code, String name, String severity, Map<String, Object> params) {
        var schema = new LinkedHashMap<String, ParamSchema>();
        for (String key : params.keySet()) {
            boolean percent = Set.of("threshold", "minOwnFundsShare").contains(key);
            String label = switch (key) { case "threshold" -> "Порог уверенности"; case "minOwnFundsShare" -> "Минимальная доля собственных средств"; case "tolerance" -> "Допустимое расхождение"; default -> "Предупреждать за, дней"; };
            schema.put(key, new ParamSchema(percent ? "percent" : "daysBeforeExpiry".equals(key) ? "integer" : "money", label, percent ? "%" : "tolerance".equals(key) ? "₽" : "дней", 0, percent ? 100 : 1_000_000_000, true));
        }
        rules.put(code, new Rule(code, name, null, true, severity, List.of(), params, Map.copyOf(schema), 1L, "Система", Instant.now()));
    }
    public synchronized List<Program> programs(boolean activeOnly) {
        return programs.values().stream().filter(program -> !activeOnly || program.active()).toList();
    }
    public synchronized Program program(String code) { return programs.get(code); }
    public synchronized List<Rule> rules() { return List.copyOf(rules.values()); }
    public synchronized Map<String, Rule> snapshot() { return Map.copyOf(rules); }
    public record Configuration(Map<String, Program> programs, Map<String, Rule> rules) { }
    public synchronized Configuration configuration() { return new Configuration(Map.copyOf(programs), Map.copyOf(rules)); }
    public synchronized List<JournalEntry> journal(int limit) {
        return journal(limit, null, null);
    }
    public synchronized List<JournalEntry> journal(int limit, Instant before, String action) {
        var entries = new ArrayList<>(journal);
        Collections.reverse(entries);
        return entries.stream().filter(entry -> before == null || entry.at().isBefore(before))
                .filter(entry -> action == null || action.equals(entry.action())).limit(Math.max(1, Math.min(limit, 1000))).toList();
    }

    public synchronized Program saveProgram(String code, Program input, boolean create, String actor) {
        if (!code.equals(input.code())) throw error(HttpStatus.BAD_REQUEST, "Код программы в адресе и теле должен совпадать");
        if (create && programs.containsKey(code)) throw error(HttpStatus.CONFLICT, "Программа с таким кодом уже существует");
        if (!create && !programs.containsKey(code)) throw error(HttpStatus.NOT_FOUND, "Программа не найдена");
        if (create && programs.size() >= 100) throw error(HttpStatus.CONFLICT, "Достигнут лимит программ");
        var previous = programs.get(code);
        if (!create) checkVersion(input.version(), previous.version());
        if (input.documents().stream().noneMatch(document -> "REQUIRED".equals(document.requirement()))) throw error(HttpStatus.BAD_REQUEST, "В программе нужен хотя бы один обязательный документ");
        var types = EnumSet.noneOf(DocumentType.class);
        for (var document : input.documents()) {
            if (document.type() == DocumentType.UNKNOWN || !types.add(document.type())) throw error(HttpStatus.BAD_REQUEST, "Типы документов программы должны быть известными и не повторяться");
            if ("CONDITIONAL".equals(document.requirement()) && (document.condition() == null || document.condition().isBlank())) throw error(HttpStatus.BAD_REQUEST, "Укажите условие для условного документа");
            if ("CONDITIONAL".equals(document.requirement()) && ATTRIBUTES.stream().noneMatch(attribute -> attribute.code().equals(document.conditionAttribute()))) throw error(HttpStatus.BAD_REQUEST, "Укажите известный признак для условного документа");
        }
        long version = previous == null ? 1 : previous.version() + 1;
        var program = new Program(code, input.name().trim(), input.active(), List.copyOf(input.documents()), input.description(), version, actor == null ? "Администратор" : actor, Instant.now());
        var next = new LinkedHashMap<>(programs); next.put(code, program);
        var entries = changed("PROGRAM_CHANGED", "Изменена программа «" + program.name() + "»", actor, previous, program, version, "PROGRAM", code);
        persist(next, rules, entries);
        programs.put(code, program);
        journal.clear(); journal.addAll(entries);
        return program;
    }

    public synchronized Rule saveRule(String code, Rule input, String actor) {
        if (!rules.containsKey(code)) throw error(HttpStatus.NOT_FOUND, "Правило не найдено");
        if (!code.equals(input.code())) throw error(HttpStatus.BAD_REQUEST, "Код правила в адресе и теле должен совпадать");
        var previous = rules.get(code);
        checkVersion(input.version(), previous.version());
        var params = input.params() == null ? Map.<String, Object>of() : input.params();
        var expected = rules.get(code).params();
        if (!params.keySet().equals(expected.keySet())) throw error(HttpStatus.BAD_REQUEST, "Сохраняйте все параметры правила; неизвестные параметры не поддерживаются");
        for (var entry : params.entrySet()) {
            if (!(entry.getValue() instanceof Number)) throw error(HttpStatus.BAD_REQUEST, "Параметр «" + entry.getKey() + "» должен быть числом");
            BigDecimal value;
            try { value = new BigDecimal(entry.getValue().toString()); }
            catch (NumberFormatException exception) { throw error(HttpStatus.BAD_REQUEST, "Некорректный числовой параметр"); }
            if (value.signum() < 0 || value.compareTo(new BigDecimal("1000000000")) > 0) throw error(HttpStatus.BAD_REQUEST, "Параметр вне допустимого диапазона");
            if (Set.of("threshold", "minOwnFundsShare").contains(entry.getKey()) && value.compareTo(BigDecimal.ONE) > 0) throw error(HttpStatus.BAD_REQUEST, "Доля или уверенность должна быть от 0 до 1");
            if ("daysBeforeExpiry".equals(entry.getKey()) && value.stripTrailingZeros().scale() > 0) throw error(HttpStatus.BAD_REQUEST, "Срок указывается целым числом дней");
        }
        if (input.documentTypes() != null && input.documentTypes().contains(DocumentType.UNKNOWN)) throw error(HttpStatus.BAD_REQUEST, "Для правила укажите типы из справочника");
        var rule = new Rule(code, input.name().trim(), input.description(), input.enabled(), input.severity(),
                input.documentTypes() == null ? List.of() : List.copyOf(input.documentTypes()), Map.copyOf(params), previous.paramSchema(), previous.version() + 1,
                actor == null ? "Администратор" : actor, Instant.now());
        var next = new LinkedHashMap<>(rules); next.put(code, rule);
        var entries = changed("RULE_CHANGED", "Изменено правило «" + rule.name() + "»", actor, previous, rule, rule.version(), "RULE", code);
        persist(programs, next, entries);
        rules.put(code, rule);
        journal.clear(); journal.addAll(entries);
        return rule;
    }
    private List<JournalEntry> changed(String action, String message, String actor, Object before, Object after, Long version, String type, String id) {
        var entries = new ArrayList<>(journal);
        entries.add(new JournalEntry(UUID.randomUUID(), Instant.now(), actor == null ? "Администратор" : actor,
                "ADMIN", action, message, null, null, "OK", null, before, after, version, type, id));
        return entries;
    }
    public synchronized void denied(String actor, String actorType, String path) {
        denied(actor, actorType, path, "Недостаточно прав");
    }
    public synchronized void denied(String actor, String actorType, String path, String reason) {
        var entries = new ArrayList<>(journal);
        entries.add(new JournalEntry(UUID.randomUUID(), Instant.now(), actor == null ? "Неизвестный пользователь" : actor,
                actorType, "ACCESS_DENIED", "Доступ к операции запрещён", null, null, "FAILED", reason + ": " + path, null, null, null, "APPLICATION", path));
        persist(programs, rules, entries); journal.clear(); journal.addAll(entries);
    }
    private void persist(Map<String, Program> programs, Map<String, Rule> rules, List<JournalEntry> entries) {
        if (store != null) store.write("settings", new Snapshot(programs, rules, entries));
    }
    private static void checkVersion(Long requested, Long current) {
        if (!Objects.equals(requested, current)) throw error(HttpStatus.CONFLICT, "Настройку уже изменил другой администратор. Обновите данные и повторите изменение.");
    }
    private static ResponseStatusException error(HttpStatus status, String detail) { return new ResponseStatusException(status, detail); }
}
