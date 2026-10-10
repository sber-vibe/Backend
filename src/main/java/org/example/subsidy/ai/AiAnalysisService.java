
package org.example.subsidy.ai;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.Semaphore;

@Service
public class AiAnalysisService {

    private final DocumentReader reader;
    private final VisionAnalysisClient aiClient;

    // Один запрос к GPU одновременно.
    private final Semaphore gpuSlot = new Semaphore(1, true);

    public AiAnalysisService(
            DocumentReader reader,
            VisionAnalysisClient aiClient) {

        this.reader = reader;
        this.aiClient = aiClient;
    }


    public String analyze(byte[] data, String filename)
            throws IOException {

        if (!gpuSlot.tryAcquire()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI analysis is busy. Try again later."
            );
        }

        try {
            DocumentContent content =
                    reader.read(data, filename);

            String rawJson = aiClient.analyzeContent(
                    content.text(),
                    content.images()
            );

            return AiResultPostprocessor.process(rawJson);

        } catch (ResourceAccessException e) {

            // Соединение не установлено или запрос превысил лимит.
            if (isTimeout(e)) {
                throw new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "AI model response timeout",
                        e
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI provider is unavailable",
                    e
            );

        } catch (RestClientResponseException e) {

            // Ollama вернула HTTP-ошибку.
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI provider returned an HTTP error",
                    e
            );

        } catch (RestClientException e) {

            // Например, не удалось прочитать HTTP-ответ.
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI provider communication error",
                    e
            );

        } catch (IllegalStateException e) {

            // Некорректный или незавершённый ответ модели.
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI provider returned an invalid result",
                    e
            );

        } finally {
            // Освобождаем доступ к GPU даже при ошибке.
            gpuSlot.release();
        }
    }

    private static boolean isTimeout(Throwable error) {

        Throwable current = error;

        while (current != null) {

            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}
