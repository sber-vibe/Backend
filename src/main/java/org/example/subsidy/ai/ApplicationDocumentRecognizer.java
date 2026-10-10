package org.example.subsidy.ai;

import java.util.*;
import org.example.subsidy.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.example.subsidy.Models.*;

@Service
public class ApplicationDocumentRecognizer implements DocumentRecognizer {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AiAnalysisService analysis;
    private final RemoteAiClient remote;
    public ApplicationDocumentRecognizer(AiAnalysisService analysis) { this(analysis, null); }
    @org.springframework.beans.factory.annotation.Autowired
    public ApplicationDocumentRecognizer(AiAnalysisService analysis, RemoteAiClient remote) { this.analysis = analysis; this.remote = remote; }

    @Override
    public Recognition recognize(byte[] content, String filename) throws Exception {
        return parse(remote != null && remote.enabled() ? remote.recognize(content, filename) : analysis.recognizeDocument(content, filename));
    }

    static Recognition parse(String response) {
        var root = JSON.readTree(response);
        if (root == null || !root.isObject() || root.get("document_type") == null || !root.get("document_type").isString()
                || root.get("fields") == null || !root.get("fields").isArray() || root.get("fields").size() > 100) throw new IllegalStateException("Invalid recognition response");
        DocumentType type = DocumentType.valueOf(root.get("document_type").asString());
        Double confidence = confidence(root.get("confidence"));
        var fields = new LinkedHashMap<String, ExtractedField>();
        for (var item : root.get("fields")) {
            if (!item.isObject() || item.get("name") == null || !item.get("name").isString()) throw new IllegalStateException("Invalid document field");
            String name = FieldValues.canonicalName(item.get("name").asString());
            if (!name.matches("[a-z][a-z0-9_]{0,63}") || fields.containsKey(name)) throw new IllegalStateException("Invalid or duplicate field name");
            var raw = item.get("value");
            if (raw != null && !raw.isNull() && !raw.isString() && !raw.isNumber()) throw new IllegalStateException("Invalid field value");
            Object value;
            try { value = FieldValues.normalize(name, raw == null || raw.isNull() ? null : raw.isString() ? raw.asString() : raw.toString()); }
            catch (IllegalArgumentException exception) { value = null; }
            var source = item.get("source");
            if (source != null && !source.isNull() && (!source.isString() || source.asString().length() > 4000)) throw new IllegalStateException("Invalid source fragment");
            fields.put(name, new ExtractedField(name, value, value == null ? "NOT_RECOGNIZED" : "RECOGNIZED",
                    confidence(item.get("confidence")), source == null || source.isNull() ? null : source.asString(), false, null, null, null));
        }
        for (String name : List.of("company_name", "inn", "kpp", "project_name", "requested_amount")) fields.putIfAbsent(name, new ExtractedField(name, null, "NOT_RECOGNIZED", null, null, false, null, null, null));
        return new Recognition(type, confidence, List.copyOf(fields.values()));
    }

    private static Double confidence(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isNumber()) throw new IllegalStateException("Invalid confidence");
        return FieldValues.confidence(Double.valueOf(node.toString()));
    }
}
