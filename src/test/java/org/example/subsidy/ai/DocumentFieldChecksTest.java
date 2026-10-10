
package org.example.subsidy.ai;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class DocumentFieldChecksTest {

    @Test
    void acceptsRussianDate() {
        assertEquals(
                LocalDate.of(2020, 5, 12),
                DocumentFieldChecks.parseDate("12.05.2020")
                        .orElseThrow()
        );
    }

    @Test
    void acceptsIsoDate() {
        assertEquals(
                LocalDate.of(2026, 10, 10),
                DocumentFieldChecks.parseDate("2026-10-10")
                        .orElseThrow()
        );
    }

    @Test
    void rejectsImpossibleDate() {
        assertTrue(
                DocumentFieldChecks.parseDate("31.02.2026")
                        .isEmpty()
        );
    }

    @Test
    void handlesLeapYears() {
        assertTrue(
                DocumentFieldChecks.parseDate("29.02.2024")
                        .isPresent()
        );

        assertTrue(
                DocumentFieldChecks.parseDate("29.02.2025")
                        .isEmpty()
        );
    }

    @Test
    void parsesRussianMoney() {
        BigDecimal result = DocumentFieldChecks
                .parseAmount("1 250 000,50 руб.")
                .orElseThrow();

        assertEquals(
                0,
                result.compareTo(new BigDecimal("1250000.50"))
        );
    }

    @Test
    void rejectsOcrMistakesInMoney() {
        assertTrue(
                DocumentFieldChecks.parseAmount("1 25О 000")
                        .isEmpty()
        );

        assertTrue(
                DocumentFieldChecks.parseAmount("1.250.000,50")
                        .isEmpty()
        );
    }

    @Test
    void handlesMissingValues() {
        assertTrue(
                DocumentFieldChecks.parseDate(null).isEmpty()
        );

        assertTrue(
                DocumentFieldChecks.parseAmount("").isEmpty()
        );
    }
}
