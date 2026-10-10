
package org.example.subsidy.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiChunkAnalyzerTest {

    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    private static final String APPLICATION_FIELDS = """
            {
              "organization": "ООО Ромашка",
              "inn": "7707083893",
              "ogrn": "",
              "kpp": "",
              "document_date": "12.05.2020",
              "requested_amount": "1250000",
              "signatory": "",
              "purpose": "",
              "uncertain_fields": []
            }
            """;


    @Test
    void retriesMissingApplicationAmount() {

        VisionAnalysisClient client =
                mock(VisionAnalysisClient.class);

        when(client.classifyDocument(anyString(), anyList()))
                .thenReturn(AiDocumentContract.APPLICATION);

        String missingAmount = APPLICATION_FIELDS.replace(
                "\"requested_amount\": \"1250000\"",
                "\"requested_amount\": \"\""
        );

        when(client.analyzeFields(
                anyString(),
                anyList(),
                eq(AiDocumentContract.APPLICATION)
        )).thenReturn(missingAmount);

        when(client.extractRequestedAmount(anyString()))
                .thenReturn("2 964 196,00");

        DocumentContent content = new DocumentContent(
                """
                Заявление о предоставлении субсидии
                Бюджет проекта
                Общая стоимость: 6 306 800,00
                Собственные средства: 3 342 604,00
                Запрашиваемая сумма
                Итого
                2 964 196,00
                """,
                List.of()
        );

        String raw = AiChunkAnalyzer.analyze(
                content, client
        );

        JsonNode result = JSON.readTree(raw);

        assertEquals(
                "2 964 196,00",
                result.get("requested_amount").asString()
        );

        verify(client).extractRequestedAmount(
                contains("Запрашиваемая сумма")
        );
    }


    @Test
    void splitsLongText() {

        String text = "А".repeat(9000);

        List<String> chunks =
                AiChunkAnalyzer.splitText(text);

        assertEquals(3, chunks.size());
        assertEquals(text, String.join("", chunks));
    }

    @Test
    void analyzesApplication() {

        VisionAnalysisClient client =
                mock(VisionAnalysisClient.class);

        when(client.classifyDocument(anyString(), anyList()))
                .thenReturn(AiDocumentContract.APPLICATION);

        when(client.analyzeFields(
                anyString(),
                anyList(),
                eq(AiDocumentContract.APPLICATION)
        )).thenReturn(APPLICATION_FIELDS);

        String raw = AiChunkAnalyzer.analyze(
                new DocumentContent(
                        "Заявление о предоставлении субсидии",
                        List.of()
                ),
                client
        );

        JsonNode result = JSON.readTree(raw);

        assertEquals(
                AiDocumentContract.APPLICATION,
                result.get("document_type").asString()
        );

        assertEquals(
                "1250000",
                result.get("requested_amount").asString()
        );

        verify(client, times(1)).analyzeFields(
                anyString(),
                anyList(),
                eq(AiDocumentContract.APPLICATION)
        );
    }

    @Test
    void unknownDocumentDoesNotExtractFields() {

        VisionAnalysisClient client =
                mock(VisionAnalysisClient.class);

        when(client.classifyDocument(anyString(), anyList()))
                .thenReturn(AiDocumentContract.UNKNOWN);

        String raw = AiChunkAnalyzer.analyze(
                new DocumentContent("Неизвестный документ", List.of()),
                client
        );

        JsonNode result = JSON.readTree(raw);

        assertEquals(
                AiDocumentContract.UNKNOWN,
                result.get("document_type").asString()
        );

        assertEquals(2, result.size());

        verify(client, never()).analyzeFields(
                anyString(), anyList(), anyString()
        );
    }

    @Test
    void rejectsExcessiveChunkCount() {

        VisionAnalysisClient client =
                mock(VisionAnalysisClient.class);

        assertThrows(
                IllegalStateException.class,
                () -> AiChunkAnalyzer.analyze(
                        new DocumentContent(
                                "А".repeat(165000),
                                List.of()
                        ),
                        client
                )
        );

        verifyNoInteractions(client);
    }
}
