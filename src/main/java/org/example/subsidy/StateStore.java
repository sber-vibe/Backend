package org.example.subsidy;

import java.sql.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Atomic database snapshots, including the data and audit trail of each operation. */
@Service
public class StateStore {
    private final String url;
    private final JsonMapper json = JsonMapper.builder().build();
    public StateStore(@Value("${storage.jdbc-url:jdbc:h2:file:./data/subsidy}") String url) {
        this.url = url;
        try {
            if (url.startsWith("jdbc:h2:file:./data/")) Files.createDirectories(Path.of("data"));
            try (var connection = connect(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS stored_state (state_key VARCHAR(100) PRIMARY KEY, payload CLOB NOT NULL)");
            }
        } catch (Exception exception) { throw new IllegalStateException("Не удалось открыть постоянное хранилище", exception); }
    }
    private Connection connect() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    public synchronized <T> T read(String key, Class<T> type) {
        try (var connection = connect(); var query = connection.prepareStatement("SELECT payload FROM stored_state WHERE state_key = ?")) {
            query.setString(1, key);
            try (var result = query.executeQuery()) { return result.next() ? json.readValue(result.getString(1), type) : null; }
        } catch (Exception exception) { throw failure(exception); }
    }
    public synchronized void write(String key, Object value) {
        String payload = json.writeValueAsString(value);
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try (var update = connection.prepareStatement("MERGE INTO stored_state (state_key, payload) KEY(state_key) VALUES (?, ?)")) {
                update.setString(1, key); update.setString(2, payload); update.executeUpdate(); connection.commit();
            } catch (Exception exception) { connection.rollback(); throw exception; }
        } catch (Exception exception) { throw failure(exception); }
    }
    private static ResponseStatusException failure(Exception exception) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Не удалось сохранить или прочитать данные. Повторите операцию после восстановления хранилища.", exception);
    }
}
