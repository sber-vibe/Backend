package org.example.subsidy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Set;

/** Values shared by model results, manual corrections and arithmetic checks. */
public final class FieldValues {
    private static final Set<String> MONEY = Set.of("requested_amount", "amount", "total_expenses", "own_funds",
            "expense_items", "balance", "total_cost", "cost", "expenses", "sum");
    private static final Set<String> DATES = Set.of("date", "issue_date", "valid_until", "document_date");
    private FieldValues() { }

    public static String canonicalName(String name) {
        return switch (name) {
            case "organization", "organization_name" -> "company_name";
            case "document_date" -> "date";
            default -> name;
        };
    }

    public static Object normalize(String name, Object value) {
        if (value == null) return null;
        if (!(value instanceof String) && !(value instanceof Number)) {
            throw new IllegalArgumentException("Значение поля должно быть строкой, числом или null");
        }
        String text = value.toString().trim();
        if (text.isEmpty()) return null;
        if (text.length() > 4000) throw new IllegalArgumentException("Значение поля длиннее 4000 символов");
        if (MONEY.contains(name)) {
            String numeric = text.replaceAll("[\\s\\p{Z}₽]", "").replaceAll("(?i)руб\\.?$", "").replace(',', '.');
            if (!numeric.matches("[+-]?\\d{1,18}(?:\\.\\d{1,2})?")) {
                throw new IllegalArgumentException("Сумма должна быть числом с точностью до копеек");
            }
            return new BigDecimal(numeric);
        }
        if (DATES.contains(name)) {
            for (String pattern : new String[]{"uuuu-MM-dd", "dd.MM.uuuu", "dd/MM/uuuu"}) {
                try { return LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern)
                        .withResolverStyle(ResolverStyle.STRICT)).toString(); }
                catch (java.time.format.DateTimeParseException ignored) { }
            }
            throw new IllegalArgumentException("Укажите действительную дату в формате ГГГГ-ММ-ДД");
        }
        return text;
    }

    public static Double confidence(Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 1)) {
            throw new IllegalArgumentException("Уверенность распознавания должна быть от 0 до 1");
        }
        return value;
    }
}
