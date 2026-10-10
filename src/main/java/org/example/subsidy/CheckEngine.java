package org.example.subsidy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import static org.example.subsidy.Models.*;

/** Computes explicit outcomes from the program, extracted values and a settings snapshot. */
final class CheckEngine {
    private final Map<String, Rule> rules;
    private final List<Finding> findings = new ArrayList<>();
    private CheckEngine(Map<String, Rule> rules) { this.rules = rules; }

    static CheckReport check(Program program, List<DocumentView> documents, Map<String, Rule> rules, Map<String, Boolean> attributes) {
        return new CheckEngine(rules).run(program, documents, attributes);
    }
    private CheckReport run(Program program, List<DocumentView> documents, Map<String, Boolean> attributes) {
        var application = documents.stream().filter(document -> document.type() == DocumentType.APPLICATION).findFirst().orElse(null);
        var completeness = new ArrayList<Completeness>();
        var listed = EnumSet.noneOf(DocumentType.class);
        if (program == null) problem("PROGRAM_NOT_DETERMINED", "Программа не определена. Уточните её в заявлении или создайте заявку с выбранной программой.", null, "WARNING", null, "program_code", "PROGRAM_SELECTION");
        else {
            if (!program.active()) problem("PROGRAM_INACTIVE", "Выбранная программа больше не активна", null, "WARNING", null, "program_code", "PROGRAM_SELECTION");
            for (var required : program.documents()) {
                var ids = documents.stream().filter(document -> document.type() == required.type()).map(DocumentView::id).toList();
                Boolean applicable = "CONDITIONAL".equals(required.requirement()) ? attributes.get(required.conditionAttribute()) : Boolean.TRUE;
                if (Boolean.FALSE.equals(applicable)) continue;
                listed.add(required.type());
                String status = !ids.isEmpty() ? "FOUND" : "CONDITIONAL".equals(required.requirement()) ? applicable == null ? "CONDITIONAL_UNKNOWN" : "CONDITIONAL_MISSING" : "MISSING";
                completeness.add(new Completeness(required.type(), status, required.requirement(), ids, ids.isEmpty(), required.condition(), required.conditionAttribute()));
                if (ids.isEmpty()) {
                    if ("REQUIRED".equals(required.requirement())) add("MISSING_DOCUMENT", "Отсутствует обязательный документ: " + title(required.type()), required.type(), "ERROR", null, null, "MISSING_DOCUMENT");
                    else problem("CONDITIONAL_DOCUMENT_MISSING", applicable == null ? "Требуется проверить применимость условия «" + required.condition() + "» и наличие документа: " + title(required.type()) : "Отсутствует условный документ: " + title(required.type()) + ". Условие: " + required.condition(), required.type(), "WARNING", null, null, "CONDITIONAL_DOCUMENT");
                }
            }
        }
        if (documents.isEmpty()) problem("EMPTY_PACKAGE", "Пакет документов пустой", null, "WARNING", null, null, "DOCUMENT_PROCESSING");
        for (var type : documents.stream().map(DocumentView::type).filter(Objects::nonNull).distinct().toList()) {
            var ids = documents.stream().filter(document -> document.type() == type).map(DocumentView::id).toList();
            if (!listed.contains(type)) completeness.add(new Completeness(type, "EXTRA", "EXTRA", ids, false));
            if (ids.size() > 1) for (var id : ids) add("DUPLICATE_DOCUMENT", "Несколько документов одного типа: " + title(type), type, "WARNING", id, null, "DUPLICATE_DOCUMENT");
        }
        if (documents.stream().anyMatch(document -> !Set.of("DONE", "FAILED").contains(document.processingStatus()))) {
            return new CheckReport("PROCESSING", Instant.now(), List.of(new Finding("DOCUMENT_PROCESSING", "Документы ещё обрабатываются. Проверка завершится после распознавания.", null, "INFO", null, null)), List.copyOf(completeness));
        }
        for (var document : documents) {
            if ("FAILED".equals(document.processingStatus())) problem("PROCESSING_FAILED", document.processingError(), document.type(), "ERROR", document.id(), null, "DOCUMENT_PROCESSING");
            if (document.type() == null || document.type() == DocumentType.UNKNOWN) problem("UNKNOWN_DOCUMENT_TYPE", "Тип документа не определён. Укажите его вручную.", document.type(), "WARNING", document.id(), null, "DOCUMENT_TYPE");
            double threshold = parameter("LOW_CONFIDENCE", "threshold", new BigDecimal("0.8")).doubleValue();
            if (document.confidence() != null && document.confidence() < threshold && !"SPECIALIST".equals(document.typeSource())) add("LOW_CONFIDENCE", "Требуется подтвердить тип документа", document.type(), "WARNING", document.id(), null, "LOW_CONFIDENCE");
            for (var field : document.fields()) {
                if (field.value() != null && field.confidence() != null && field.confidence() < threshold && !field.manuallyCorrected()) add("LOW_CONFIDENCE", "Требуется проверить распознавание поля «" + field.name() + "»", document.type(), "WARNING", document.id(), field.name(), "LOW_CONFIDENCE");
                if ("valid_until".equals(field.name()) && field.value() != null) expiry(document, field);
            }
            if (document.type() == DocumentType.COST_ESTIMATE || document.type() == DocumentType.COST_JUSTIFICATION) arithmetic(document);
        }
        BigDecimal requested = number(application, "requested_amount");
        if (requested == null) add("AMOUNT_NOT_RECOGNIZED", "Не удалось проверить: сумма субсидии не распознана", DocumentType.APPLICATION, "WARNING", application == null ? null : application.id(), "requested_amount", "AMOUNT_NOT_RECOGNIZED");
        else if (requested.signum() <= 0) add("INVALID_AMOUNT", "Сумма субсидии должна быть больше нуля", DocumentType.APPLICATION, "ERROR", application.id(), "requested_amount", "INVALID_AMOUNT");
        if (application != null) for (String name : List.of("company_name", "inn", "project_name")) {
            if (value(application, name) == null) problem("REQUISITE_NOT_RECOGNIZED", "Не распознан реквизит заявления: «" + name + "»", DocumentType.APPLICATION, "WARNING", application.id(), name, "REQUISITES");
        }
        for (var document : documents) if (document != application && document.fields().stream().anyMatch(field -> "requested_amount".equals(field.name()))) {
            BigDecimal amount = number(document, "requested_amount");
            if (requested == null || amount == null) add("AMOUNTS_NOT_CHECKED", "Не удалось сверить суммы между документами: сумма не распознана", document.type(), "WARNING", document.id(), "requested_amount", "AMOUNTS_MATCH");
            else {
                boolean equal = requested.subtract(amount).abs().compareTo(parameter("AMOUNTS_MATCH", "tolerance", BigDecimal.ZERO)) <= 0;
                add("AMOUNTS_MATCH", equal ? "Сумма субсидии совпадает с заявлением" : "Сумма субсидии отличается от заявления", document.type(), equal ? "OK" : "ERROR", document.id(), "requested_amount", "AMOUNTS_MATCH");
            }
        }
        add("MANUAL_REVIEW_REQUIRED", "Подписи и соответствие регламенту требуют проверки специалистом", null, "WARNING", null, null, "MANUAL_REVIEW_REQUIRED");
        String status = findings.stream().anyMatch(finding -> "ERROR".equals(finding.severity())) ? "ERRORS"
                : findings.stream().anyMatch(finding -> "WARNING".equals(finding.severity())) ? "ATTENTION" : "PASSED";
        return new CheckReport(status, Instant.now(), List.copyOf(findings), List.copyOf(completeness));
    }

    private void arithmetic(DocumentView document) {
        BigDecimal total = number(document, "total_expenses"), own = number(document, "own_funds"), subsidy = number(document, "requested_amount");
        if (total == null || own == null || subsidy == null) {
            add("ARITHMETIC_NOT_CHECKED", "Не удалось проверить арифметику: затраты, собственные средства или сумма субсидии не распознаны", document.type(), "WARNING", document.id(), "total_expenses", "EXPENSES_SUM");
            add("OWN_FUNDS_NOT_CHECKED", "Не удалось проверить долю собственных средств: нужные суммы не распознаны", document.type(), "WARNING", document.id(), "own_funds", "OWN_FUNDS_SHARE");
            return;
        }
        boolean equal = own.add(subsidy).subtract(total).abs().compareTo(parameter("EXPENSES_SUM", "tolerance", BigDecimal.ZERO)) <= 0;
        add("EXPENSES_SUM", equal ? "Собственные средства и субсидия совпадают с общими затратами" : "Сумма собственных средств и субсидии не совпадает с общими затратами", document.type(), equal ? "OK" : "ERROR", document.id(), "total_expenses", "EXPENSES_SUM");
        if (total.signum() <= 0 || own.signum() < 0 || subsidy.signum() < 0 || own.compareTo(total) > 0) add("INVALID_OWN_FUNDS", "Собственные средства, субсидия или общие затраты указаны некорректно", document.type(), "ERROR", document.id(), "own_funds", "OWN_FUNDS_SHARE");
        else {
            BigDecimal minimum = parameter("OWN_FUNDS_SHARE", "minOwnFundsShare", BigDecimal.ZERO);
            boolean sufficient = own.compareTo(total.multiply(minimum)) >= 0;
            String share = own.multiply(new BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP).toPlainString();
            add("OWN_FUNDS_SHARE", "Доля собственных средств: " + share + "%" + (sufficient ? " — соответствует настроенному порогу" : " — ниже настроенного порога"), document.type(), sufficient ? "OK" : "ERROR", document.id(), "own_funds", "OWN_FUNDS_SHARE");
        }
    }
    private void expiry(DocumentView document, ExtractedField field) {
        LocalDate until;
        try { until = LocalDate.parse(field.value().toString()); }
        catch (java.time.format.DateTimeParseException exception) { return; }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (until.isBefore(today)) add("DOCUMENT_EXPIRED", "Срок действия документа истёк: " + until, document.type(), "ERROR", document.id(), field.name(), "DOCUMENT_EXPIRY");
        else if (!until.isAfter(today.plusDays(parameter("DOCUMENT_EXPIRY", "daysBeforeExpiry", new BigDecimal("30")).longValue()))) add("DOCUMENT_EXPIRES_SOON", "Срок действия документа скоро истекает: " + until, document.type(), "WARNING", document.id(), field.name(), "DOCUMENT_EXPIRY");
        else add("DOCUMENT_VALID", "Срок действия документа не истёк", document.type(), "OK", document.id(), field.name(), "DOCUMENT_EXPIRY");
    }
    private void add(String code, String message, DocumentType type, String severity, UUID documentId, String field, String ruleCode) {
        var rule = rules.get(ruleCode);
        if (rule != null && (!rule.enabled() || (!rule.documentTypes().isEmpty() && !rule.documentTypes().contains(type)))) return;
        // Missing numbers remain warnings; a passed numeric comparison always stays OK.
        String effective = rule == null || "OK".equals(severity) || code.endsWith("NOT_CHECKED") || "AMOUNT_NOT_RECOGNIZED".equals(code) ? severity : rule.severity();
        problem(code, message, type, effective, documentId, field, ruleCode);
    }
    private void problem(String code, String message, DocumentType type, String severity, UUID id, String field, String rule) {
        findings.add(new Finding(code, message, type, severity, id, field, rule));
    }
    private BigDecimal parameter(String rule, String name, BigDecimal fallback) {
        var setting = rules.get(rule);
        return setting == null || setting.params().get(name) == null ? fallback : new BigDecimal(setting.params().get(name).toString());
    }
    private static Object value(DocumentView document, String name) {
        if (document == null) return null;
        return document.fields().stream().filter(field -> name.equals(field.name()) && "RECOGNIZED".equals(field.status())).map(ExtractedField::value).filter(Objects::nonNull).findFirst().orElse(null);
    }
    private static BigDecimal number(DocumentView document, String name) { return value(document, name) instanceof BigDecimal number ? number : null; }
    /** Conditions: field, field=value. Unreadable/free-text conditions require human review. */
    private static Boolean condition(DocumentView application, String expression) {
        if (expression == null) return null;
        var matcher = java.util.regex.Pattern.compile("([a-z][a-z0-9_]{0,63})(?:\\s*=\\s*(.{1,200}))?").matcher(expression.trim());
        if (!matcher.matches()) return null;
        Object value = value(application, matcher.group(1));
        if (value == null) return null;
        String text = value.toString().trim();
        return matcher.group(2) != null ? text.equalsIgnoreCase(matcher.group(2).trim()) : Set.of("true", "1", "да", "yes").contains(text.toLowerCase(Locale.ROOT));
    }
    private static String title(DocumentType type) { return switch (type) {
        case APPLICATION -> "заявление"; case REGISTRATION_EXTRACT -> "выписка ЕГРЮЛ/ЕГРИП";
        case COST_ESTIMATE -> "смета расходов"; case PD_CONSENT -> "согласие на обработку персональных данных";
        case CONFORMITY_CERTIFICATE -> "сертификат соответствия"; case PROJECT_PRESENTATION -> "презентация проекта";
        case COST_JUSTIFICATION -> "обоснование затрат"; case BANK_STATEMENT -> "выписка банка";
        case REAL_ESTATE_DOCUMENT -> "документ на недвижимость"; case GUARANTEE_LETTER -> "гарантийное письмо";
        case GIMS_DECLARATION -> "декларация ГИМС"; default -> "дополнительный документ";
    }; }
}
