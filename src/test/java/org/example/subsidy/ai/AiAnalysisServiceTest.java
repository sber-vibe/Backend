
package org.example.subsidy.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAnalysisServiceTest {

    private static final String VALID_MODEL_RESULT = """
            {
              "organization": "ООО Ромашка",
              "inn": "7707083893",
              "ogrn": "",
              "kpp": "",
              "document_date": "12.05.2020",
              "requested_amount": "1250000",
              "signatory": "",
              "purpose": "Покупка оборудования",
              "uncertain_fields": []
            }
            """;

    private DocumentReader reader;
    private VisionAnalysisClient client;
    private AiAnalysisService service;

    @BeforeEach
    void setup() throws Exception {

        reader = mock(DocumentReader.class);
        client = mock(VisionAnalysisClient.class);

        service = new AiAnalysisService(reader, client);

        when(reader.read(any(byte[].class), anyString()))
                .thenReturn(
                        new DocumentContent(
                                "Тестовый документ",
                                List.of()
                        )
                );

        when(client.classifyDocument(anyString(), anyList()))
                .thenReturn(AiDocumentContract.APPLICATION);
    }

    @Test
    void returns502WhenAiReturnsMalformedJson() {

        when(client.analyzeFields(
                anyString(),
                anyList(),
                eq(AiDocumentContract.APPLICATION)
        )).thenReturn("{invalid json}");

        assertAnalysisStatus(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void returns503WhenOllamaIsUnavailable() {

        when(client.analyzeFields(
                anyString(),
                anyList(),
                eq(AiDocumentContract.APPLICATION)
        )).thenThrow(
                new ResourceAccessException(
                        "Connection refused",
                        new ConnectException("Connection refused")
                )
        );

        assertAnalysisStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void returns504WhenOllamaTimesOut() {

        when(client.analyzeFields(
                anyString(),
                anyList(),
                eq(AiDocumentContract.APPLICATION)
        )).thenThrow(
                new ResourceAccessException(
                        "Request timed out",
                        new HttpTimeoutException("Timeout")
                )
        );

        assertAnalysisStatus(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void returns502WhenAiResponseIsInvalid() {

        when(client.analyzeFields(
                anyString(),
                anyList(),
                eq(AiDocumentContract.APPLICATION)
        )).thenThrow(
                new IllegalStateException("Incomplete generation")
        );

        assertAnalysisStatus(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void successfulAnalysisReturnsValidatedResult() throws Exception {

        when(client.analyzeFields(
                anyString(),
                anyList(),
                eq(AiDocumentContract.APPLICATION)
        )).thenReturn(VALID_MODEL_RESULT);

        String result = service.analyze(
                new byte[]{1, 2, 3},
                "document.txt"
        );

        assertTrue(
                result.contains("\"document_type\":\"APPLICATION\"")
        );

        assertTrue(
                result.contains("\"needs_review\":false")
        );

        assertTrue(
                result.contains("\"requested_amount\":\"1250000\"")
        );

        verify(client).analyzeFields(
                anyString(),
                anyList(),
                eq(AiDocumentContract.APPLICATION)
        );
    }

    @Test
    void failedRequestReleasesGpuSlot() throws Exception {

        when(client.analyzeFields(
                anyString(),
                anyList(),
                eq(AiDocumentContract.APPLICATION)
        ))
                .thenThrow(
                        new ResourceAccessException(
                                "Connection refused",
                                new ConnectException("Connection refused")
                        )
                )
                .thenReturn(VALID_MODEL_RESULT);

        assertThrows(
                ResponseStatusException.class,
                () -> service.analyze(
                        new byte[]{1},
                        "document.txt"
                )
        );

        String secondResult = service.analyze(
                new byte[]{1},
                "document.txt"
        );

        assertTrue(
                secondResult.contains(
                        "\"document_type\":\"APPLICATION\""
                )
        );

        assertTrue(
                secondResult.contains("\"needs_review\":false")
        );

        verify(client, times(2)).analyzeFields(
                anyString(),
                anyList(),
                eq(AiDocumentContract.APPLICATION)
        );
    }

    private void assertAnalysisStatus(HttpStatus expected) {

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.analyze(
                        new byte[]{1, 2, 3},
                        "document.txt"
                )
        );

        assertEquals(expected, error.getStatusCode());
    }
}
