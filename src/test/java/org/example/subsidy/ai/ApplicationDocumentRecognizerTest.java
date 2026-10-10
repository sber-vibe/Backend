package org.example.subsidy.ai;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationDocumentRecognizerTest {
    @Test
    void aliasesAmountsDatesAndMissingValuesAreNormalizedWithoutGuessing() {
        var result = ApplicationDocumentRecognizer.parse("""
                {"document_type":"APPLICATION","confidence":0.91,"fields":[
                  {"name":"organization","value":"Компания","confidence":0.93,"source":"Заявитель: Компания"},
                  {"name":"requested_amount","value":"850 000,50 ₽","confidence":0.7,"source":null},
                  {"name":"document_date","value":"10.10.2026","confidence":0.9,"source":null},
                  {"name":"inn","value":null,"confidence":null,"source":null}
                ]}
                """);
        assertEquals("company_name", result.fields().get(0).name());
        assertEquals(new BigDecimal("850000.50"), result.fields().get(1).value());
        assertEquals("2026-10-10", result.fields().get(2).value());
        assertEquals("NOT_RECOGNIZED", result.fields().get(3).status());
        assertTrue(result.fields().stream().anyMatch(f -> f.name().equals("project_name") && f.value() == null));
    }
    @Test
    void invalidNumbersAndDatesBecomeNotRecognizedInsteadOfGuessed() {
        var result = ApplicationDocumentRecognizer.parse("""
                {"document_type":"APPLICATION","confidence":null,"fields":[
                  {"name":"requested_amount","value":"около миллиона","source":null},
                  {"name":"date","value":"31.02.2026","source":null}
                ]}
                """);
        assertNull(result.fields().get(0).value());
        assertNull(result.fields().get(1).value());
    }
    @Test
    void malformedResultFailsAndDoesNotInventConfidence() {
        assertThrows(RuntimeException.class, () -> ApplicationDocumentRecognizer.parse("not json"));
        assertThrows(RuntimeException.class, () -> ApplicationDocumentRecognizer.parse("{\"document_type\":\"APPLICATION\",\"confidence\":1.5,\"fields\":[]}"));
    }
}
