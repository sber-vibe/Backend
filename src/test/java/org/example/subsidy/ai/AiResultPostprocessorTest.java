
package org.example.subsidy.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class AiResultPostprocessorTest {

    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    private static final String APPLICATION = """
            {
              "document_type": "APPLICATION",
              "organization": "ООО Ромашка",
              "inn": "7707083893",
              "ogrn": "",
              "kpp": "",
              "document_date": "12.05.2020",
              "requested_amount": "1250000",
              "signatory": "",
              "purpose": "Приобретение оборудования",
              "uncertain_fields": []
            }
            """;

    @Test
    void validApplicationHasNoWarnings() {

        JsonNode result = parse(
                AiResultPostprocessor.process(APPLICATION)
        );

        assertFalse(result.get("needs_review").asBoolean());
        assertEquals(0, result.get("warnings").size());
    }

    @Test
    void missingAmountRequiresReview() {

        String raw = APPLICATION.replace(
                "\"requested_amount\": \"1250000\"",
                "\"requested_amount\": \"\""
        );

        JsonNode result = parse(
                AiResultPostprocessor.process(raw)
        );

        assertTrue(result.get("needs_review").asBoolean());
        assertTrue(
                result.get("warnings").toString()
                        .contains("requested_amount")
        );
    }

    @Test
    void invalidInnRequiresReview() {

        String raw = APPLICATION.replace(
                "7707083893",
                "7725123456"
        );

        JsonNode result = parse(
                AiResultPostprocessor.process(raw)
        );

        assertTrue(result.get("needs_review").asBoolean());
        assertTrue(
                result.get("warnings").toString().contains("ИНН")
        );
    }

    @Test
    void realEstateHasSpecificFields() {

        String raw = """
                {
                  "document_type": "REAL_ESTATE_DOCUMENT",
                  "cadastral_number": "61:44:0030601:41",
                  "area": "1000",
                  "property_purpose": "Нежилое",
                  "address": "Ростовская область",
                  "rights_holder": "",
                  "uncertain_fields": []
                }
                """;

        JsonNode result = parse(
                AiResultPostprocessor.process(raw)
        );

        assertFalse(result.get("needs_review").asBoolean());
        assertNotNull(
                result.get("fields").get("cadastral_number")
        );
        assertNull(
                result.get("fields").get("bank_kpp")
        );
    }

    @Test
    void unknownDocumentRequiresReview() {

        JsonNode result = parse(
                AiResultPostprocessor.process("""
                        {
                          "document_type": "UNKNOWN",
                          "uncertain_fields": []
                        }
                        """)
        );

        assertTrue(result.get("needs_review").asBoolean());
    }

    @Test
    void rejectsUnexpectedFields() {

        String raw = APPLICATION.replace(
                "\"uncertain_fields\": []",
                "\"uncertain_fields\": [], \"admin_override\": \"yes\""
        );

        assertThrows(
                IllegalStateException.class,
                () -> AiResultPostprocessor.process(raw)
        );
    }

    @Test
    void rejectsMalformedJson() {

        assertThrows(
                IllegalStateException.class,
                () -> AiResultPostprocessor.process("{invalid")
        );
    }

    private static JsonNode parse(String json) {
        return JSON.readTree(json);
    }
}
