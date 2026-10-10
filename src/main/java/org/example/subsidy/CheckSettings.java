package org.example.subsidy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.example.subsidy.Models.*;

/** In-memory administrative settings. Changes apply to the next check. */
@Service
public class CheckSettings {
    private final Map<String, Program> programs = new LinkedHashMap<>();
    private final Map<String, Rule> rules = new LinkedHashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();

    public CheckSettings() {
        programs.put("INNOVATION", new Program("INNOVATION", "Инновационный проект", true, List.of(
                new ProgramDocument(DocumentType.APPLICATION, "REQUIRED"),
                new ProgramDocument(DocumentType.REGISTRATION_EXTRACT, "REQUIRED"),
                new ProgramDocument(DocumentType.COST_ESTIMATE, "REQUIRED")), "Демонстрационный перечень; требует согласования с регламентом"));
        add("MISSING_DOCUMENT", "Обязательные документы", "ERROR", Map.of());
        add("DUPLICATE_DOCUMENT", "Повторные документы", "WARNING", Map.of());
        add("LOW_CONFIDENCE", "Уверенность распознавания", "WARNING", Map.of("threshold", 0.8));
        add("AMOUNT_NOT_RECOGNIZED", "Наличие суммы субсидии", "WARNING", Map.of());
        add("INVALID_AMOUNT", "Положительная сумма субсидии", "ERROR", Map.of());
        add("EXPENSES_SUM", "Арифметика затрат", "ERROR", Map.of("tolerance", 0));
        add("OWN_FUNDS_SHARE", "Доля собственных средств", "ERROR", Map.of("minOwnFundsShare", 0));
        add("AMOUNTS_MATCH", "Совпадение сумм документов", "ERROR", Map.of("tolerance", 0));
        add("DOCUMENT_EXPIRY", "Срок действия документа", "WARNING", Map.of("daysBeforeExpiry", 30));
        add("MANUAL_REVIEW_REQUIRED", "Проверка подписей и регламента специалистом", "WARNING", Map.of());
    }

    private void add(String code, String name, String severity, Map<String, Object> params) {
        rules.put(code, new Rule(code, name, null, true, severity, List.of(), params));
    }
    public synchronized List<Program> programs(boolean activeOnly) {
        return programs.values().stream().filter(program -> !activeOnly || program.active()).toList();
    }
    public synchronized Program program(String code) { return programs.get(code); }
    public synchronized List<Rule> rules() { return List.copyOf(rules.values()); }
    public synchronized Map<String, Rule> snapshot() { return Map.copyOf(rules); }
    public synchronized List<JournalEntry> journal(int limit) {
        var entries = new ArrayList<>(journal);
        Collections.reverse(entries);
        return entries.stream().limit(Math.max(1, Math.min(limit, 1000))).toList();
    }

    public synchronized Program saveProgram(String code, Program input, boolean create, String actor) {
        if (!code.equals(input.code())) throw error(HttpStatus.BAD_REQUEST, "Код программы в адресе и теле должен совпадать");
        if (create && programs.containsKey(code)) throw error(HttpStatus.CONFLICT, "Программа с таким кодом уже существует");
        if (!create && !programs.containsKey(code)) throw error(HttpStatus.NOT_FOUND, "Программа не найдена");
        if (create && programs.size() >= 100) throw error(HttpStatus.CONFLICT, "Достигнут лимит программ");
        var types = EnumSet.noneOf(DocumentType.class);
        for (var document : input.documents()) {
            if (document.type() == DocumentType.UNKNOWN || !types.add(document.type())) throw error(HttpStatus.BAD_REQUEST, "Типы документов программы должны быть известными и не повторяться");
            if ("CONDITIONAL".equals(document.requirement()) && (document.condition() == null || document.condition().isBlank())) throw error(HttpStatus.BAD_REQUEST, "Укажите условие для условного документа");
        }
        var program = new Program(code, input.name().trim(), input.active(), List.copyOf(input.documents()), input.description());
        programs.put(code, program);
        changed("PROGRAM_CHANGED", "Изменена программа «" + program.name() + "»", actor);
        return program;
    }

    public synchronized Rule saveRule(String code, Rule input, String actor) {
        if (!rules.containsKey(code)) throw error(HttpStatus.NOT_FOUND, "Правило не найдено");
        if (!code.equals(input.code())) throw error(HttpStatus.BAD_REQUEST, "Код правила в адресе и теле должен совпадать");
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
        var rule = new Rule(code, input.name().trim(), input.description(), input.enabled(), input.severity(),
                input.documentTypes() == null ? List.of() : List.copyOf(input.documentTypes()), Map.copyOf(params));
        rules.put(code, rule);
        changed("RULE_CHANGED", "Изменено правило «" + rule.name() + "»", actor);
        return rule;
    }
    private void changed(String action, String message, String actor) {
        if (journal.size() >= 1000) journal.remove(0);
        journal.add(new JournalEntry(UUID.randomUUID(), Instant.now(), actor == null ? "Администратор" : actor,
                "ADMIN", action, message, null, null, "OK", null));
    }
    private static ResponseStatusException error(HttpStatus status, String detail) { return new ResponseStatusException(status, detail); }
}
