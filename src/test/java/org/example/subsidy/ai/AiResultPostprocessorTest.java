
package org.example.subsidy.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class AiResultPostprocessorTest {

    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    private static final String VALID_INPUT = """
            {
              "document_type": "APPLICATION",
              "organization": "ООО Ромашка",
              "inn": "7707083893",
              "kpp": "773601001",
              "bank_inn": "",
              "bank_kpp": "",
              "document_date": "12.05.2020",
              "requested_amount": "",
              "purpose": "Покупка оборудования",
              "uncertain_fields": []
            }
            """;

    @Test
    void validDocumentHasNoWarnings() {

        JsonNode result = parse(
                AiResultPostprocessor.process(VALID_INPUT)
        );

        assertFalse(result.get("needs_review").asBoolean());
        assertEquals(0, result.get("warnings").size());

        assertEquals(
                "7707083893",
                result.get("fields").get("inn").asString()
        );

        assertTrue(
                result.get("fields")
                        .get("requested_amount").isNull()
        );
    }

    @Test
    void invalidInnRequiresReview() {

        String input = VALID_INPUT.replace(
                "7707083893", "7725123456"
        );

        JsonNode result = parse(
                AiResultPostprocessor.process(input)
        );

        assertTrue(result.get("needs_review").asBoolean());

        assertTrue(
                result.get("warnings").toString()
                        .contains("ИНН заявителя")
        );

        // Ошибочное значение сохраняется.
        assertEquals(
                "7725123456",
                result.get("fields").get("inn").asString()
        );
    }

    @Test
    void unknownDocumentClearsPurpose() {

        String input = VALID_INPUT.replace(
                "\"APPLICATION\"", "\"UNKNOWN\""
        );

        JsonNode result = parse(
                AiResultPostprocessor.process(input)
        );

        assertEquals(
                "UNKNOWN",
                result.get("document_type").asString()
        );

        assertTrue(
                result.get("fields").get("purpose").isNull()
        );
    }

    @Test
    void uncertainFieldRequiresReview() {

        String input = VALID_INPUT.replace(
                "\"uncertain_fields\": []",
                "\"uncertain_fields\": [\"document_date\"]"
        );

        JsonNode result = parse(
                AiResultPostprocessor.process(input)
        );

        assertTrue(result.get("needs_review").asBoolean());

        assertEquals(
                "document_date",
                result.get("uncertain_fields")
                        .get(0).asString()
        );
    }


    @Test
    void invalidDateRequiresReview() {

        String input = VALID_INPUT.replace(
                "12.05.2020", "31.02.2026"
        );

        JsonNode result = parse(
                AiResultPostprocessor.process(input)
        );

        assertTrue(result.get("needs_review").asBoolean());

        assertTrue(
                result.get("warnings").toString()
                        .contains("Дата документа")
        );
    }


    @Test
    void rejectsUnexpectedField() {

        String input = VALID_INPUT.replace(
                "\"uncertain_fields\": []",
                "\"uncertain_fields\": [], "
                        + "\"extra_field\": \"unexpected\""
        );

        assertThrows(
                IllegalStateException.class,
                () -> AiResultPostprocessor.process(input)
        );
    }

    @Test
    void rejectsMalformedJson() {

        assertThrows(
                IllegalStateException.class,
                () -> AiResultPostprocessor.process(
                        "{\"document_type\":"
                )
        );
    }

    @Test
    void rejectsUnknownUncertainField() {

        String input = VALID_INPUT.replace(
                "\"uncertain_fields\": []",
                "\"uncertain_fields\": [\"admin_override\"]"
        );

        assertThrows(
                IllegalStateException.class,
                () -> AiResultPostprocessor.process(input)
        );
    }

    @Test
    void rejectsUnsupportedDocumentType() {

        String input = VALID_INPUT.replace(
                "\"APPLICATION\"",
                "\"APPROVED\""
        );

        assertThrows(
                IllegalStateException.class,
                () -> AiResultPostprocessor.process(input)
        );
    }


    @Test
    void invalidAmountRequiresReview() {

        String input = VALID_INPUT.replace(
                "\"requested_amount\": \"\"",
                "\"requested_amount\": \"1 25О 000\""
        );

        JsonNode result = parse(
                AiResultPostprocessor.process(input)
        );

        assertTrue(result.get("needs_review").asBoolean());

        assertTrue(
                result.get("warnings").toString()
                        .contains("сумму")
        );
    }


    @Test
    void missingFieldIsRejected() {

        String input = VALID_INPUT.replace(
                "\"bank_kpp\": \"\",", ""
        );

        assertThrows(
                IllegalStateException.class,
                () -> AiResultPostprocessor.process(input)
        );
    }

    private static JsonNode parse(String json) {
        return JSON.readTree(json);
    }
}
