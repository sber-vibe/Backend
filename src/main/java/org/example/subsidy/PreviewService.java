package org.example.subsidy;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.example.subsidy.ai.IsolatedDocumentContentReader;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PreviewService {
    public record Preview(boolean ready, byte[] html) { }
    private static final class State { byte[] html; String error; boolean completed; }
    private final Map<UUID, State> previews = new LinkedHashMap<>(16, 0.75f, true);
    private final IsolatedDocumentContentReader reader;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20), task -> { var thread = new Thread(task, "office-preview"); thread.setDaemon(true); return thread; });
    public PreviewService(IsolatedDocumentContentReader reader) { this.reader = reader; }
    @PreDestroy public void stop() { workers.shutdownNow(); }

    public synchronized Preview request(UUID id, String filename, Supplier<byte[]> original) {
        if (!filename.toLowerCase(Locale.ROOT).matches(".+\\.(doc|docx|xls|xlsx)$")) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Предпросмотр доступен для DOC, DOCX, XLS и XLSX");
        var state = previews.get(id);
        if (state != null) {
            if (state.error != null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, state.error);
            return new Preview(state.completed, state.html == null ? null : state.html.clone());
        }
        // Limit both pending jobs and cached previews. Completed entries can be rebuilt on demand.
        if (previews.size() >= 32) {
            var iterator = previews.entrySet().iterator();
            while (iterator.hasNext()) if (iterator.next().getValue().completed) { iterator.remove(); break; }
            if (previews.size() >= 32) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Очередь предпросмотра занята. Повторите запрос позже.");
        }
        var pending = new State();
        byte[] content = original.get();
        previews.put(id, pending);
        try {
            workers.execute(() -> {
                byte[] html = null; String failure = null;
                try { html = reader.preview(content, filename); }
                catch (Exception exception) {
                    failure = "Не удалось подготовить предпросмотр. Проверьте, что документ читается, не защищён паролем и не превышает ограничения парсера.";
                }
                synchronized (PreviewService.this) { pending.html = html; pending.error = failure; pending.completed = true; }
            });
        } catch (RejectedExecutionException exception) {
            previews.remove(id);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Очередь предпросмотра занята. Повторите запрос позже.");
        }
        return new Preview(false, null);
    }
    public synchronized void delete(UUID id) { previews.remove(id); }
}
