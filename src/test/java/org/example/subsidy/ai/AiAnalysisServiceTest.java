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
    }


    @Test
    void returns502WhenAiReturnsMalformedJson() {

        when(client.analyzeContent(anyString(), anyList()))
                .thenReturn("{invalid json}");

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.analyze(
                        new byte[]{1, 2, 3},
                        "document.txt"
                )
        );

        assertEquals(
                HttpStatus.BAD_GATEWAY,
                error.getStatusCode()
        );
    }


    @Test
    void returns503WhenOllamaIsUnavailable() {

        when(client.analyzeContent(anyString(), anyList()))
                .thenThrow(
                        new ResourceAccessException(
                                "Connection refused",
                                new ConnectException("Connection refused")
                        )
                );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.analyze(
                        new byte[]{1, 2, 3},
                        "document.txt"
                )
        );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                error.getStatusCode()
        );
    }

    @Test
    void returns504WhenOllamaTimesOut() {

        when(client.analyzeContent(anyString(), anyList()))
                .thenThrow(
                        new ResourceAccessException(
                                "Request timed out",
                                new HttpTimeoutException("Timeout")
                        )
                );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.analyze(
                        new byte[]{1, 2, 3},
                        "document.txt"
                )
        );

        assertEquals(
                HttpStatus.GATEWAY_TIMEOUT,
                error.getStatusCode()
        );
    }

    @Test
    void returns502WhenAiResponseIsInvalid() {

        when(client.analyzeContent(anyString(), anyList()))
                .thenThrow(
                        new IllegalStateException(
                                "Incomplete generation"
                        )
                );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.analyze(
                        new byte[]{1, 2, 3},
                        "document.txt"
                )
        );

        assertEquals(
                HttpStatus.BAD_GATEWAY,
                error.getStatusCode()
        );
    }

    @Test
    void successfulAnalysisReturnsValidatedResult() throws Exception {
        String modelResult = """
                {
                  "document_type": "APPLICATION",
                  "organization": "ООО Ромашка",
                  "inn": "7707083893",
                  "kpp": "",
                  "bank_inn": "",
                  "bank_kpp": "",
                  "document_date": "12.05.2020",
                  "requested_amount": "",
                  "purpose": "Покупка оборудования",
                  "uncertain_fields": []
                }
                """;

        when(client.analyzeContent(anyString(), anyList()))
                .thenReturn(modelResult);

        String result = service.analyze(
                new byte[]{1, 2, 3}, "document.txt");

        assertTrue(result.contains("\"document_type\":\"APPLICATION\""));
        assertTrue(result.contains("\"needs_review\":false"));
        verify(client).analyzeContent(eq("Тестовый документ"), anyList());
    }

    @Test
    void failedRequestReleasesGpuSlot() throws Exception {
        String modelResult = """
                {
                  "document_type": "UNKNOWN",
                  "organization": "",
                  "inn": "",
                  "kpp": "",
                  "bank_inn": "",
                  "bank_kpp": "",
                  "document_date": "",
                  "requested_amount": "",
                  "purpose": "",
                  "uncertain_fields": []
                }
                """;

        when(client.analyzeContent(anyString(), anyList()))
                .thenThrow(new ResourceAccessException(
                        "Connection refused",
                        new ConnectException("Connection refused")))
                .thenReturn(modelResult);

        assertThrows(ResponseStatusException.class,
                () -> service.analyze(new byte[]{1}, "document.txt"));

        String secondResult = service.analyze(
                new byte[]{1}, "document.txt");

        assertTrue(secondResult.contains("\"document_type\":\"UNKNOWN\""));
        verify(client, times(2)).analyzeContent(anyString(), anyList());
    }

}
