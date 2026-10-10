
package org.example.subsidy.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class DocumentFieldChecks {

    private static final DateTimeFormatter RUSSIAN_DATE =
            DateTimeFormatter.ofPattern("d.M.uuuu", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern AMOUNT = Pattern.compile(
            "(?:[0-9]+|[0-9]{1,3}(?: [0-9]{3})+)(?:[.,][0-9]{1,2})?"
    );

    private DocumentFieldChecks() {
    }

    public static Optional<LocalDate> parseDate(String raw) {

        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        String value = raw.strip();

        try {
            return Optional.of(
                    LocalDate.parse(value, RUSSIAN_DATE)
            );
        } catch (DateTimeParseException ignored) {
            try {
                return Optional.of(
                        LocalDate.parse(
                                value, DateTimeFormatter.ISO_LOCAL_DATE
                        )
                );
            } catch (DateTimeParseException ignoredAgain) {
                return Optional.empty();
            }
        }
    }

    public static Optional<BigDecimal> parseAmount(String raw) {

        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        String value = raw
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .strip();

        // Убираем распространённые обозначения рублей,
        // но не изменяем исходное значение в JSON.
        value = value.replaceFirst(
                "(?iu)\\s*(?:₽|руб\\.|руб|рублей|рубля)\\s*$",
                ""
        ).strip();

        if (!AMOUNT.matcher(value).matches()) {
            return Optional.empty();
        }

        try {
            return Optional.of(new BigDecimal(
                    value.replace(" ", "").replace(',', '.')
            ));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
