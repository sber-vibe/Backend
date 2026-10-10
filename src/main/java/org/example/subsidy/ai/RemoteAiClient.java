package org.example.subsidy.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Trusted LAN document analysis. The key is used only by the backend. */
@Service
public class RemoteAiClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final String endpoint;
    private final String key;
    private final RestClient client;
    private final Semaphore slot = new Semaphore(1, true);

    public RemoteAiClient(@Value("${AI_REMOTE_URL:}") String endpoint,
                          @Value("${AI_REMOTE_API_KEY:}") String key) {
        this.endpoint = endpoint.trim();
        this.key = key.trim();
        if (!this.endpoint.isEmpty()) {
            URI uri = URI.create(this.endpoint);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("AI_REMOTE_URL must be an HTTP(S) endpoint without credentials or query");
            if (this.key.length() < 32) throw new IllegalArgumentException("AI_REMOTE_API_KEY must contain at least 32 characters");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(180));
        client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean enabled() { return !endpoint.isEmpty(); }

    public String recognize(byte[] content, String filename) throws InterruptedException {
        slot.acquire();
        try {
            var multipart = new LinkedMultiValueMap<String, Object>();
            multipart.add("file", new ByteArrayResource(content) {
                @Override public String getFilename() { return filename == null ? "document.bin" : filename; }
            });
            String response;
            try {
                response = client.post().uri(endpoint).header("X-Api-Key", key)
                        .contentType(MediaType.MULTIPART_FORM_DATA).body(multipart).retrieve().body(String.class);
            } catch (RestClientException exception) {
                throw new IllegalStateException("Удалённый сервис анализа недоступен или отклонил запрос. Проверьте подключение и ключ.");
            }
            return adapt(response);
        } finally { slot.release(); }
    }

    static String adapt(String response) {
        if (response == null || response.length() > 1_000_000) throw new IllegalStateException("Некорректный ответ удалённого анализа");
        var root = JSON.readTree(response);
        if (root == null || !root.isObject() || root.get("document_type") == null || !root.get("document_type").isString()
                || root.get("fields") == null) throw new IllegalStateException("Некорректный ответ удалённого анализа");
        ObjectNode result = JSON.createObjectNode();
        String type = root.get("document_type").asString();
        result.put("document_type", "PERSONAL_DATA_CONSENT".equals(type) ? "PD_CONSENT" : type);
        if (root.get("confidence") != null) result.set("confidence", root.get("confidence"));
        var fields = root.get("fields");
        if (fields.isArray()) result.set("fields", fields);
        else if (fields.isObject() && fields.size() <= 100) {
            var entries = result.putArray("fields");
            for (var entry : fields.properties()) {
                var field = entries.addObject();
                field.put("name", entry.getKey());
                if (entry.getValue().isContainer()) field.put("value", JSON.writeValueAsString(entry.getValue()));
                else field.set("value", entry.getValue());
                field.putNull("confidence");
                field.putNull("source");
            }
            if ("BANK_STATEMENT".equals(type) && fields.get("accounts") != null && !fields.has("account_balance")) {
                var balance = entries.addObject();
                balance.put("name", "account_balance");
                var accounts = fields.get("accounts");
                if (accounts.isArray() && accounts.size() == 1 && accounts.get(0).isObject()
                        && accounts.get(0).get("account_balance") != null)
                    balance.set("value", accounts.get(0).get("account_balance"));
                else balance.putNull("value");
                balance.putNull("confidence");
                balance.putNull("source");
            }
        } else throw new IllegalStateException("Некорректные реквизиты удалённого анализа");
        return JSON.writeValueAsString(result);
    }
}
