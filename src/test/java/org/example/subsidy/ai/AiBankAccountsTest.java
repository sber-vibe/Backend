
package org.example.subsidy.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiBankAccountsTest {

    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    private static final String BANK_FIELDS = """
            {
              "organization": "ИП Тестов Иван Иванович",
              "inn": "",
              "passport_series": "6015",
              "passport_number": "865696",
              "passport_issued_by": "",
              "passport_issue_date": "",
              "document_date": "03.09.2026",
              "accounts": [
                {
                  "account_number": "40817810790947585285",
                  "account_balance": "600000.00"
                },
                {
                  "account_number": "40817810790947585286",
                  "account_balance": "3000000.00"
                }
              ],
              "uncertain_fields": []
            }
            """;

    @Test
    void preservesTwoAccountsAndPassport() {

        VisionAnalysisClient client =
                mock(VisionAnalysisClient.class);

        when(client.classifyDocument(
                anyString(),
                anyList()
        )).thenReturn(AiDocumentContract.BANK_STATEMENT);

        when(client.analyzeFields(
                anyString(),
                anyList(),
                eq(AiDocumentContract.BANK_STATEMENT)
        )).thenReturn(BANK_FIELDS);

        String raw = AiChunkAnalyzer.analyze(
                new DocumentContent(
                        "Банковская справка о двух счетах",
                        List.of()
                ),
                client
        );

        JsonNode result = process(raw);

        JsonNode fields = result.get("fields");
        JsonNode accounts = fields.get("accounts");
        JsonNode passport = fields.get("passport_details");

        assertEquals(2, accounts.size());

        assertEquals(
                "600000.00",
                accounts.get(0)
                        .get("account_balance").asString()
        );

        assertEquals(
                "3000000.00",
                accounts.get(1)
                        .get("account_balance").asString()
        );

        assertEquals(
                "6015",
                passport.get("series").asString()
        );

        assertEquals(
                "865696",
                passport.get("number").asString()
        );

        assertNull(fields.get("passport_series"));
        assertNull(fields.get("passport_number"));

        assertFalse(
                result.get("needs_review").asBoolean()
        );
    }

    @Test
    void missingAccountNumberRequiresReview() {

        String raw = BANK_FIELDS.replace(
                "\"40817810790947585286\"",
                "\"\""
        );

        JsonNode result = processBank(raw);

        assertTrue(
                result.get("needs_review").asBoolean()
        );

        assertTrue(
                result.get("warnings").toString()
                        .contains("Не найден номер счёта")
        );
    }

    @Test
    void invalidPassportSeriesRequiresReview() {

        String raw = BANK_FIELDS.replace(
                "\"passport_series\": \"6015\"",
                "\"passport_series\": \"60AA\""
        );

        JsonNode result = processBank(raw);

        assertTrue(
                result.get("needs_review").asBoolean()
        );

        assertTrue(
                result.get("warnings").toString()
                        .contains("серии паспорта")
        );
    }

    @Test
    void missingOptionalPassportIsAllowed() {

        String raw = BANK_FIELDS
                .replace(
                        "\"passport_series\": \"6015\"",
                        "\"passport_series\": \"\""
                )
                .replace(
                        "\"passport_number\": \"865696\"",
                        "\"passport_number\": \"\""
                );

        JsonNode result = processBank(raw);

        assertTrue(
                result.get("fields")
                        .get("passport_details").isNull()
        );

        assertFalse(
                result.get("needs_review").asBoolean()
        );
    }

    @Test
    void doesNotTreatPassportAsBankClientInn() {

        String raw = BANK_FIELDS.replace(
                "\"inn\": \"\"",
                "\"inn\": \"6015865696\""
        );

        JsonNode result = processBank(raw);

        assertTrue(
                result.get("fields")
                        .get("inn").isNull()
        );

        assertEquals(
                "6015",
                result.get("fields")
                        .get("passport_details")
                        .get("series").asString()
        );

        assertFalse(
                result.get("needs_review").asBoolean()
        );

        assertEquals(
                0,
                result.get("warnings").size()
        );
    }

    @Test
    void consentReturnsStructuredPassport() {

        String raw = """
                {
                  "document_type": "PERSONAL_DATA_CONSENT",
                  "full_name": "Иванов Иван Иванович",
                  "passport_series": "6015",
                  "passport_number": "865696",
                  "passport_issued_by": "МВД России",
                  "passport_issue_date": "15.06.2020",
                  "document_date": "03.09.2026",
                  "uncertain_fields": []
                }
                """;

        JsonNode result = process(raw);

        JsonNode passport = result
                .get("fields")
                .get("passport_details");

        assertEquals(
                "МВД России",
                passport.get("issued_by").asString()
        );

        assertEquals(
                "15.06.2020",
                passport.get("issue_date").asString()
        );

        assertFalse(
                result.get("needs_review").asBoolean()
        );
    }

    @Test
    void distinctAccountsWithSameBalanceArePreserved() {

        VisionAnalysisClient client =
                mock(VisionAnalysisClient.class);

        when(client.classifyDocument(
                anyString(),
                anyList()
        )).thenReturn(AiDocumentContract.BANK_STATEMENT);

        when(client.analyzeFields(
                anyString(),
                anyList(),
                eq(AiDocumentContract.BANK_STATEMENT)
        )).thenReturn(
                BANK_FIELDS.replace(
                        "3000000.00",
                        "600000.00"
                )
        );

        String raw = AiChunkAnalyzer.analyze(
                new DocumentContent(
                        "Банковская справка",
                        List.of()
                ),
                client
        );

        JsonNode result = JSON.readTree(raw);

        assertEquals(
                2,
                result.get("accounts").size()
        );
    }

    private static JsonNode processBank(String raw) {

        String combined = """
                {"document_type":"BANK_STATEMENT",
                """ + raw.substring(1);

        return process(combined);
    }

    private static JsonNode process(String raw) {

        return JSON.readTree(
                AiResultPostprocessor.process(raw)
        );
    }
}
