
package org.example.subsidy.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class VisionAnalysisClientRecoveryTest {

    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    @Test
    void recoversIndividualEntrepreneurFromGuaranteeLetter() {

        String source = """
                Гарантийное письмо

                В соответствии с Порядком предоставления субсидии

                ИП Леонов Игорь Александрович, Ростовская область,
                станица Багаевская

                в лице

                заявляет о своем намерении участвовать в конкурсе
                """;

        String raw = """
                {
                  "organization": "",
                  "inn": "",
                  "guarantee_amount": "",
                  "document_date": "",
                  "signatory": "",
                  "uncertain_fields": ["organization"]
                }
                """;

        String result = VisionAnalysisClient
                .recoverGuaranteeOrganization(raw, source);

        JsonNode json = JSON.readTree(result);

        assertEquals(
                "ИП Леонов Игорь Александрович",
                json.get("organization").asString()
        );

        assertEquals(
                "",
                json.get("guarantee_amount").asString()
        );

        assertEquals(
                "",
                json.get("document_date").asString()
        );

        assertEquals(0, json.get("uncertain_fields").size());
    }

    @Test
    void doesNotInventOrganizationWhenAbsent() {

        String raw = """
                {
                  "organization": "",
                  "uncertain_fields": []
                }
                """;

        String result = VisionAnalysisClient
                .recoverGuaranteeOrganization(
                        raw,
                        "Гарантийное письмо без реквизитов"
                );

        assertEquals(raw, result);
    }
}
