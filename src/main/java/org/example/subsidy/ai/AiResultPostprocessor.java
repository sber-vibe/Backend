
package org.example.subsidy.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
                    "AI response must be a JSON object"
            );
        }

        if (source.size() != AiDocumentContract.RAW_FIELDS.size()) {
            throw new IllegalStateException(
                    "Unexpected AI response fields"
            );
        }


        String type = requiredString(source, AiDocumentContract.DOCUMENT_TYPE);

        if (!AiDocumentContract.DOCUMENT_TYPES.contains(type)) {
            throw new IllegalStateException(
                    "Unsupported document type: " + type
            );
        }

        JsonNode uncertain = source.get(AiDocumentContract.UNCERTAIN_FIELDS);

        if (uncertain == null || !uncertain.isArray()) {
            throw new IllegalStateException(
                    "Invalid uncertain_fields"
            );
        }

        ObjectNode result = JSON.createObjectNode();
        result.put(AiDocumentContract.DOCUMENT_TYPE, type);

        ObjectNode fields = result.putObject("fields");

        for (String name : AiDocumentContract.DATA_FIELDS) {
            String value = requiredString(source, name).trim();

            if (name.equals("purpose")
                    && type.equals(AiDocumentContract.UNKNOWN)) {
                value = "";
            }

            if (value.isEmpty()) {
                fields.putNull(name);
            } else {
                fields.put(name, value);
            }
        }

        List<String> warnings = new ArrayList<>();

        if (type.equals(AiDocumentContract.UNKNOWN)) {
            warnings.add(
                    "Тип не определён текущим классификатором"
            );
        }

        checkInn(fields, "inn", "заявителя", warnings);
        checkInn(fields, "bank_inn", "банка", warnings);

        checkKpp(fields, "kpp", "заявителя", warnings);
        checkKpp(fields, "bank_kpp", "банка", warnings);

        checkDocumentDate(fields, warnings);
        checkRequestedAmount(fields, warnings);

        ArrayNode uncertainOutput =
                result.putArray(AiDocumentContract.UNCERTAIN_FIELDS);

        for (JsonNode item : uncertain) {

            if (!item.isString()) {
                throw new IllegalStateException(
                        "Invalid uncertain field"
                );
            }

            String name = item.asString();

            if (!AiDocumentContract.UNCERTAIN_FIELD_NAMES.contains(name)) {
                throw new IllegalStateException(
                        "Unknown uncertain field: " + name
                );
            }

            uncertainOutput.add(name);

            warnings.add(
                    "Требуется проверить распознавание: " + name
            );
        }

        ArrayNode warningArray = result.putArray("warnings");

        for (String warning : warnings) {
            warningArray.add(warning);
        }

        result.put("needs_review", !warnings.isEmpty());

        return JSON.writeValueAsString(result);
    }

    private static String requiredString(
            JsonNode source, String field) {

        JsonNode value = source.get(field);

        if (value == null || !value.isString()) {
            throw new IllegalStateException(
                    "Invalid AI field: " + field
            );
        }

        return value.asString();
    }

    private static void checkInn(
            ObjectNode fields,
            String field,
            String owner,
            List<String> warnings) {

        JsonNode node = fields.get(field);

        if (node == null || node.isNull()) {
            return;
        }

        String inn = node.asString();

        if (!InnValidator.isValid(inn)) {
            warnings.add(
                    "ИНН " + owner
                            + " не прошёл проверку контрольных цифр"
            );
        }
    }

    private static void checkKpp(
            ObjectNode fields,
            String field,
            String owner,
            List<String> warnings) {

        JsonNode node = fields.get(field);

        if (node == null || node.isNull()) {
            return;
        }

        if (!node.asString().matches("[0-9]{9}")) {
            warnings.add(
                    "КПП " + owner + " имеет неверный формат"
            );
        }
    }

    private static void checkDocumentDate(
            ObjectNode fields,
            List<String> warnings) {

        JsonNode node = fields.get("document_date");

        if (node == null || node.isNull()) {
            return;
        }

        String date = node.asString();

        if (DocumentFieldChecks.parseDate(date).isEmpty()) {
            warnings.add(
                    "Дата документа имеет неверный формат "
                            + "или не существует: " + date
            );
        }
    }

    private static void checkRequestedAmount(
            ObjectNode fields,
            List<String> warnings) {

        JsonNode node = fields.get("requested_amount");

        if (node == null || node.isNull()) {
            return;
        }

        String original = node.asString();

        var amount = DocumentFieldChecks.parseAmount(original);

        if (amount.isEmpty()) {
            warnings.add(
                    "Не удалось однозначно распознать сумму: "
                            + original
            );
            return;
        }

        if (amount.get().signum() <= 0) {
            warnings.add(
                    "Запрашиваемая сумма должна быть положительной"
            );
        }
    }

}
