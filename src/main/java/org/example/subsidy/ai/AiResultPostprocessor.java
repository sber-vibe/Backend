
package org.example.subsidy.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AiResultPostprocessor {

    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    private static final Set<String> CRITICAL_UNCERTAIN = Set.of(
            "requested_amount",
            "total_expenses",
            "own_funds",
            "guarantee_amount",
            "accounts",
            "cadastral_number",
            "certificate_number",
            "valid_until"
    );

    private AiResultPostprocessor() {
    }

    public static String process(String rawJson) {

        JsonNode source;

        try {
            source = JSON.readTree(rawJson);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Malformed AI JSON",
                    e
            );
        }

        if (source == null || !source.isObject()) {
            throw new IllegalStateException(
                    "AI response must be an object"
            );
        }

        String type = requiredString(
                source,
                AiDocumentContract.DOCUMENT_TYPE
        );

        if (!AiDocumentContract.DOCUMENT_TYPES.contains(type)) {
            throw new IllegalStateException(
                    "Unsupported document type: " + type
            );
        }

        List<String> expected =
                AiDocumentContract.fieldsFor(type);

        if (source.size() != expected.size() + 2) {
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

        result.put(
                AiDocumentContract.DOCUMENT_TYPE,
                type
        );

        ObjectNode fields = result.putObject("fields");

        boolean passportAdded = false;

        for (String field : expected) {

            if (AiDocumentContract.PASSPORT_FIELDS.contains(field)) {

                if (!passportAdded) {
                    putPassportDetails(source, fields);
                    passportAdded = true;
                }

                continue;
            }

            if (field.equals(AiDocumentContract.ACCOUNTS)) {

                JsonNode accounts = source.get(field);

                if (accounts == null
                        || !accounts.isArray()
                        || accounts.size() > 30) {

                    throw new IllegalStateException(
                            "Invalid accounts array"
                    );
                }

                ArrayNode output = fields.putArray(field);

                for (JsonNode account : accounts) {

                    if (account == null
                            || !account.isObject()
                            || account.size() != 2) {

                        throw new IllegalStateException(
                                "Invalid account object"
                        );
                    }

                    ObjectNode entry = output.addObject();

                    putNullableString(
                            account,
                            entry,
                            "account_number"
                    );

                    putNullableString(
                            account,
                            entry,
                            "account_balance"
                    );
                }

                continue;
            }

            putNullableString(source, fields, field);
        }

        List<String> blocking = new ArrayList<>();
        List<String> advisory = new ArrayList<>();

        if (type.equals(AiDocumentContract.UNKNOWN)) {
            blocking.add("Тип документа не определён");
        }

        String bankInn =
                type.equals(AiDocumentContract.BANK_STATEMENT)
                        ? value(fields, "inn")
                        : null;

        String passportNumber = passportDigits(fields);

        boolean passportWasInn =
                bankInn != null
                        && passportNumber != null
                        && bankInn.equals(passportNumber);

        boolean unverifiedBankInn =
                bankInn != null && !passportWasInn;

        if (bankInn != null) {

            fields.putNull("inn");

            if (unverifiedBankInn) {
                advisory.add(
                        "ИНН клиента не подтверждён "
                                + "исходным документом"
                );
            }
        }

        for (String required :
                AiDocumentContract.requiredFor(type)) {

            JsonNode node = fields.get(required);

            if (node == null
                    || node.isNull()
                    || (node.isArray() && node.isEmpty())) {

                blocking.add(
                        "Не найден обязательный реквизит: "
                                + required
                );
            }
        }

        checkInn(fields, blocking);
        checkKpp(fields, blocking);
        checkDates(fields, blocking);
        checkAmounts(fields, blocking);
        checkCadastralNumber(fields, blocking);
        checkAccounts(fields, blocking);
        checkPassport(fields, blocking);
        checkRightsHolder(fields, blocking);

        Set<String> uncertainNames =
                new LinkedHashSet<>();

        for (JsonNode item : uncertain) {

            if (!item.isString()) {
                throw new IllegalStateException(
                        "Invalid uncertain field"
                );
            }

            String name = item.asString();

            if (!name.equals(AiDocumentContract.DOCUMENT_TYPE)
                    && !expected.contains(name)) {

                throw new IllegalStateException(
                        "Unknown uncertain field: " + name
                );
            }

            if (AiDocumentContract.PASSPORT_FIELDS.contains(name)) {
                name = AiDocumentContract.PASSPORT_DETAILS;
            }

            if (passportWasInn && name.equals("inn")) {
                continue;
            }

            uncertainNames.add(name);
        }

        if (unverifiedBankInn) {
            uncertainNames.add("inn");
        }

        ArrayNode uncertainOutput = result.putArray(
                AiDocumentContract.UNCERTAIN_FIELDS
        );

        for (String name : uncertainNames) {

            uncertainOutput.add(name);

            if (!unverifiedBankInn || !name.equals("inn")) {

                advisory.add(
                        "Низкая уверенность распознавания: "
                                + name
                );
            }

            if (CRITICAL_UNCERTAIN.contains(name)) {

                blocking.add(
                        "Неоднозначный критичный реквизит: "
                                + name
                );
            }
        }

        ArrayNode warnings = result.putArray("warnings");

        for (String message : blocking) {
            warnings.add(message);
        }

        for (String message : advisory) {
            warnings.add(message);
        }

        result.put(
                "needs_review",
                !blocking.isEmpty()
        );

        return JSON.writeValueAsString(result);
    }

    private static String passportDigits(
            ObjectNode fields
    ) {
        JsonNode passport = fields.get(
                AiDocumentContract.PASSPORT_DETAILS
        );

        if (passport == null || passport.isNull()) {
            return null;
        }

        String series = nullableValue(
                passport, "series"
        );

        String number = nullableValue(
                passport, "number"
        );

        if (series == null
                || number == null
                || !series.matches("[0-9]{4}")
                || !number.matches("[0-9]{6}")) {

            return null;
        }

        return series + number;
    }

    private static void putPassportDetails(
            JsonNode source,
            ObjectNode fields
    ) {
        String series = requiredString(
                source, "passport_series"
        ).strip();

        String number = requiredString(
                source, "passport_number"
        ).strip();

        String issuedBy = requiredString(
                source, "passport_issued_by"
        ).strip();

        String issueDate = requiredString(
                source, "passport_issue_date"
        ).strip();

        if (series.isEmpty()
                && number.isEmpty()
                && issuedBy.isEmpty()
                && issueDate.isEmpty()) {

            fields.putNull(
                    AiDocumentContract.PASSPORT_DETAILS
            );

            return;
        }

        ObjectNode passport = fields.putObject(
                AiDocumentContract.PASSPORT_DETAILS
        );

        putNullableValue(passport, "series", series);
        putNullableValue(passport, "number", number);
        putNullableValue(passport, "issued_by", issuedBy);
        putNullableValue(passport, "issue_date", issueDate);
    }

    private static void putNullableString(
            JsonNode source,
            ObjectNode destination,
            String name
    ) {
        putNullableValue(
                destination,
                name,
                requiredString(source, name).strip()
        );
    }

    private static void putNullableValue(
            ObjectNode object,
            String name,
            String value
    ) {
        if (value.isEmpty()) {
            object.putNull(name);
        } else {
            object.put(name, value);
        }
    }

    private static String requiredString(
            JsonNode object,
            String field
    ) {
        JsonNode node = object.get(field);

        if (node == null || !node.isString()) {
            throw new IllegalStateException(
                    "Invalid AI field: " + field
            );
        }

        return node.asString();
    }

    private static void checkInn(
            ObjectNode fields,
            List<String> blocking
    ) {
        String inn = value(fields, "inn");

        if (inn != null && !InnValidator.isValid(inn)) {

            blocking.add(
                    "ИНН не прошёл проверку контрольных цифр"
            );
        }
    }

    private static void checkKpp(
            ObjectNode fields,
            List<String> blocking
    ) {
        String kpp = value(fields, "kpp");

        if (kpp != null && !kpp.matches("[0-9]{9}")) {
            blocking.add("КПП имеет неверный формат");
        }
    }

    private static void checkDates(
            ObjectNode fields,
            List<String> blocking
    ) {
        for (String field : List.of(
                "document_date",
                "issue_date",
                "valid_until"
        )) {

            String raw = value(fields, field);

            if (raw == null) {
                continue;
            }

            var parsed = DocumentFieldChecks.parseDate(raw);

            if (parsed.isEmpty()) {

                blocking.add(
                        "Некорректная дата: " + field
                );

                continue;
            }

            LocalDate date = parsed.get();

            if (!field.equals("valid_until")
                    && date.isAfter(LocalDate.now())) {

                blocking.add(
                        "Дата находится в будущем: " + field
                );
            }

            if (field.equals("valid_until")
                    && date.isBefore(LocalDate.now())) {

                blocking.add(
                        "Срок действия документа истёк"
                );
            }
        }
    }

    private static void checkAmounts(
            ObjectNode fields,
            List<String> blocking
    ) {
        for (String field : List.of(
                "requested_amount",
                "total_expenses",
                "own_funds",
                "guarantee_amount"
        )) {

            String raw = value(fields, field);

            if (raw == null) {
                continue;
            }

            var amount = DocumentFieldChecks.parseAmount(raw);

            if (amount.isEmpty()) {

                blocking.add(
                        "Некорректная сумма: " + field
                );

            } else if (amount.get().compareTo(
                    BigDecimal.ZERO
            ) <= 0) {

                blocking.add(
                        "Сумма должна быть положительной: "
                                + field
                );
            }
        }
    }

    private static void checkAccounts(
            ObjectNode fields,
            List<String> blocking
    ) {
        JsonNode accounts = fields.get(
                AiDocumentContract.ACCOUNTS
        );

        if (accounts == null || !accounts.isArray()) {
            return;
        }

        int index = 0;

        for (JsonNode account : accounts) {

            index++;

            JsonNode number = account.get(
                    "account_number"
            );

            JsonNode balance = account.get(
                    "account_balance"
            );

            if (number == null || number.isNull()) {

                blocking.add(
                        "Не найден номер счёта №" + index
                );

            } else if (!number.asString()
                    .matches("[0-9]{20}")) {

                blocking.add(
                        "Неверный формат номера счёта №" + index
                );
            }

            if (balance == null || balance.isNull()) {

                blocking.add(
                        "Не найден остаток по счёту №" + index
                );

            } else {

                var amount = DocumentFieldChecks.parseAmount(
                        balance.asString()
                );

                if (amount.isEmpty()) {

                    blocking.add(
                            "Не удалось распознать остаток счёта №"
                                    + index
                    );

                } else if (amount.get().signum() < 0) {

                    blocking.add(
                            "Отрицательный остаток по счёту №"
                                    + index
                    );
                }
            }
        }
    }

    private static void checkPassport(
            ObjectNode fields,
            List<String> blocking
    ) {
        JsonNode passport = fields.get(
                AiDocumentContract.PASSPORT_DETAILS
        );

        if (passport == null || passport.isNull()) {
            return;
        }

        String series = nullableValue(
                passport, "series"
        );

        String number = nullableValue(
                passport, "number"
        );

        String date = nullableValue(
                passport, "issue_date"
        );

        if (series != null
                && !series.matches("[0-9]{4}")) {

            blocking.add(
                    "Неверный формат серии паспорта"
            );
        }

        if (number != null
                && !number.matches("[0-9]{6}")) {

            blocking.add(
                    "Неверный формат номера паспорта"
            );
        }

        if ((series == null) != (number == null)) {
            blocking.add("Неполные паспортные реквизиты");
        }

        if (date != null) {

            var parsed = DocumentFieldChecks.parseDate(date);

            if (parsed.isEmpty()) {

                blocking.add(
                        "Некорректная дата выдачи паспорта"
                );

            } else if (parsed.get().isAfter(LocalDate.now())) {

                blocking.add(
                        "Дата выдачи паспорта находится в будущем"
                );
            }
        }
    }

    private static void checkCadastralNumber(
            ObjectNode fields,
            List<String> blocking
    ) {
        String number = value(
                fields, "cadastral_number"
        );

        if (number != null
                && !number.matches(
                "[0-9]{2}:[0-9]{2}:[0-9]{6,7}:[0-9]+"
        )) {

            blocking.add(
                    "Кадастровый номер имеет неверный формат"
            );
        }
    }

    private static void checkRightsHolder(
            ObjectNode fields,
            List<String> blocking
    ) {
        String holder = value(
                fields, "rights_holder"
        );

        if (holder == null) {
            return;
        }

        String lower = holder.toLowerCase(Locale.ROOT);

        if (lower.contains("роскадастр")
                || lower.contains("росреестр")) {

            fields.putNull("rights_holder");

            blocking.add(
                    "Правообладатель спутан с органом, "
                            + "выдавшим документ"
            );
        }
    }

    private static String nullableValue(
            JsonNode object,
            String name
    ) {
        JsonNode node = object.get(name);

        return node == null || node.isNull()
                ? null
                : node.asString();
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
