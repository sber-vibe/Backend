
package org.example.subsidy.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class AiResultPostprocessor {

    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    private AiResultPostprocessor() {
    }

    public static String process(String rawJson) {

        JsonNode source;

        try {
            source = JSON.readTree(rawJson);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Malformed AI JSON", e
            );
        }

        if (source == null || !source.isObject()) {
            throw new IllegalStateException(
                    "AI response must be an object"
            );
        }

        String type = requiredString(
                source, AiDocumentContract.DOCUMENT_TYPE
        );

        if (!AiDocumentContract.DOCUMENT_TYPES.contains(type)) {
            throw new IllegalStateException(
                    "Unsupported document type: " + type
            );
        }

        List<String> expectedFields =
                AiDocumentContract.fieldsFor(type);

        if (source.size() != expectedFields.size() + 2) {
            throw new IllegalStateException(
                    "Unexpected AI response fields"
            );
        }

        JsonNode uncertain = source.get(
                AiDocumentContract.UNCERTAIN_FIELDS
        );

        if (uncertain == null || !uncertain.isArray()) {
            throw new IllegalStateException(
                    "Invalid uncertain_fields"
            );
        }

        ObjectNode result = JSON.createObjectNode();
        result.put(AiDocumentContract.DOCUMENT_TYPE, type);

        ObjectNode fields = result.putObject("fields");

        for (String field : expectedFields) {

            String value = requiredString(source, field).strip();

            if (value.isEmpty()) {
                fields.putNull(field);
            } else {
                fields.put(field, value);
            }
        }

        List<String> warnings = new ArrayList<>();

        if (type.equals(AiDocumentContract.UNKNOWN)) {
            warnings.add("Тип документа не определён");
        }

        for (String required : AiDocumentContract.requiredFor(type)) {

            JsonNode value = fields.get(required);

            if (value == null || value.isNull()) {
                warnings.add(
                        "Не найден обязательный реквизит: "
                                + required
                );
            }
        }

        checkInn(fields, warnings);
        checkKpp(fields, warnings);
        checkDates(fields, warnings);
        checkAmounts(fields, warnings);
        checkCadastralNumber(fields, warnings);

        ArrayNode uncertainOutput = result.putArray(
                AiDocumentContract.UNCERTAIN_FIELDS
        );

        for (JsonNode item : uncertain) {

            if (!item.isString()) {
                throw new IllegalStateException(
                        "Invalid uncertain field"
                );
            }

            String name = item.asString();

            if (!name.equals(AiDocumentContract.DOCUMENT_TYPE)
                    && !expectedFields.contains(name)) {
                throw new IllegalStateException(
                        "Unknown uncertain field: " + name
                );
            }

            uncertainOutput.add(name);

            warnings.add(
                    "Требуется проверить распознавание: " + name
            );
        }

        ArrayNode warningOutput = result.putArray("warnings");

        for (String warning : warnings) {
            warningOutput.add(warning);
        }

        result.put("needs_review", !warnings.isEmpty());

        return JSON.writeValueAsString(result);
    }

    private static String requiredString(
            JsonNode object,
            String field
    ) {
        JsonNode value = object.get(field);

        if (value == null || !value.isString()) {
            throw new IllegalStateException(
                    "Invalid AI field: " + field
            );
        }

        return value.asString();
    }

    private static void checkInn(
            ObjectNode fields,
            List<String> warnings
    ) {
        String inn = value(fields, "inn");

        if (inn != null && !InnValidator.isValid(inn)) {
            warnings.add(
                    "ИНН не прошёл проверку контрольных цифр"
            );
        }
    }

    private static void checkKpp(
            ObjectNode fields,
            List<String> warnings
    ) {
        String kpp = value(fields, "kpp");

        if (kpp != null && !kpp.matches("[0-9]{9}")) {
            warnings.add("КПП имеет неверный формат");
        }
    }

    private static void checkDates(
            ObjectNode fields,
            List<String> warnings
    ) {
        for (String field : List.of(
                "document_date", "issue_date", "valid_until"
        )) {
            String raw = value(fields, field);

            if (raw == null) {
                continue;
            }

            var parsed = DocumentFieldChecks.parseDate(raw);

            if (parsed.isEmpty()) {
                warnings.add(
                        "Некорректная дата: " + field
                );
                continue;
            }

            LocalDate date = parsed.get();

            if (!field.equals("valid_until")
                    && date.isAfter(LocalDate.now())) {
                warnings.add(
                        "Дата находится в будущем: " + field
                );
            }

            if (field.equals("valid_until")
                    && date.isBefore(LocalDate.now())) {
                warnings.add(
                        "Срок действия документа истёк"
                );
            }
        }
    }

    private static void checkAmounts(
            ObjectNode fields,
            List<String> warnings
    ) {
        for (String field : List.of(
                "requested_amount",
                "total_expenses",
                "own_funds",
                "account_balance",
                "guarantee_amount"
        )) {
            String raw = value(fields, field);

            if (raw == null) {
                continue;
            }

            var amount = DocumentFieldChecks.parseAmount(raw);

            if (amount.isEmpty()) {
                warnings.add(
                        "Некорректная сумма: " + field
                );
                continue;
            }

            if (!field.equals("account_balance")
                    && amount.get().compareTo(BigDecimal.ZERO) <= 0) {
                warnings.add(
                        "Сумма должна быть положительной: " + field
                );
            }
        }
    }

    private static void checkCadastralNumber(
            ObjectNode fields,
            List<String> warnings
    ) {
        String cadastral = value(fields, "cadastral_number");

        if (cadastral != null
                && !cadastral.matches(
                "[0-9]{2}:[0-9]{2}:[0-9]{6,7}:[0-9]+"
        )) {
            warnings.add(
                    "Кадастровый номер имеет неверный формат"
            );
        }
    }

    private static String value(
            ObjectNode fields,
            String name
    ) {
        JsonNode node = fields.get(name);

        return node == null || node.isNull()
                ? null
                : node.asString();
    }
}
