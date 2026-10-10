
package org.example.subsidy.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AiChunkAnalyzer {

    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    private static final int CHUNK_SIZE = 4000;
    private static final int MAX_CHUNKS = 40;

    private static final String MONEY =
            "(?:\\d{1,3}(?:[ \\t\\u00A0\\u202F]\\d{3})+|\\d+)[,.]\\d{2}";

    private static final Pattern TOTAL_INLINE = Pattern.compile(
            "(?im)^\\h*Итого\\h+(" + MONEY + ")\\h+("
                    + MONEY + ")\\h*$"
    );

    private static final Pattern TOTAL_AFTER = Pattern.compile(
            "(?im)^\\h*Итого\\h*\\R\\h*("
                    + MONEY + ")\\h*\\R\\h*("
                    + MONEY + ")\\h*$"
    );

    private static final Pattern TOTAL_BEFORE = Pattern.compile(
            "(?im)^\\h*(" + MONEY + ")\\h*\\R\\h*("
                    + MONEY + ")\\h*\\R\\h*Итого\\h*$"
    );

    private AiChunkAnalyzer() {
    }

    static String analyze(
            DocumentContent content,
            VisionAnalysisClient client
    ) {
        List<String> chunks = splitText(content.text());

        if (chunks.size() > MAX_CHUNKS) {
            throw new IllegalStateException(
                    "Document contains too many chunks"
            );
        }

        List<byte[]> primaryImages =
                content.text().isBlank()
                        || content.text().contains(": изображение №")
                        ? content.images()
                        : List.of();

        String type = client.classifyDocument(
                classificationExcerpt(content.text()),
                primaryImages
        );

        if (!AiDocumentContract.DOCUMENT_TYPES.contains(type)) {
            throw new IllegalStateException(
                    "Invalid document type"
            );
        }

        List<String> fields = AiDocumentContract.fieldsFor(type);

        ObjectNode merged = JSON.createObjectNode();
        merged.put(AiDocumentContract.DOCUMENT_TYPE, type);

        for (String field : fields) {
            merged.put(field, "");
        }

        Set<String> uncertainFields = new LinkedHashSet<>();

        if (!type.equals(AiDocumentContract.UNKNOWN)) {

            for (int i = 0; i < chunks.size(); i++) {

                List<byte[]> images = i == 0
                        ? primaryImages
                        : List.of();

                String raw = client.analyzeFields(
                        chunks.get(i),
                        images,
                        type
                );

                JsonNode response;

                try {
                    response = JSON.readTree(raw);
                } catch (RuntimeException e) {
                    throw new IllegalStateException(
                            "Invalid extraction JSON", e
                    );
                }

                if (response == null
                        || !response.isObject()
                        || response.size() != fields.size() + 1) {
                    throw new IllegalStateException(
                            "Invalid extraction structure"
                    );
                }

                for (String field : fields) {

                    JsonNode node = response.get(field);

                    if (node == null || !node.isString()) {
                        throw new IllegalStateException(
                                "Invalid extraction field: " + field
                        );
                    }

                    String value = node.asString().strip();

                    if (value.isEmpty()) {
                        continue;
                    }

                    String previous = merged.get(field).asString();

                    if (previous.isEmpty()) {
                        merged.put(field, value);
                    } else if (!sameValue(previous, value)) {
                        uncertainFields.add(field);
                    }
                }

                JsonNode uncertain = response.get(
                        AiDocumentContract.UNCERTAIN_FIELDS
                );

                if (uncertain == null || !uncertain.isArray()) {
                    throw new IllegalStateException(
                            "Invalid uncertain fields"
                    );
                }

                for (JsonNode item : uncertain) {

                    if (!item.isString()
                            || !fields.contains(item.asString())) {
                        throw new IllegalStateException(
                                "Invalid uncertain field name"
                        );
                    }

                    uncertainFields.add(item.asString());
                }
            }
        }

        if (type.equals(AiDocumentContract.APPLICATION)) {

            String tableAmount = requestedAmountFromTable(
                    content.text()
            );

            if (!tableAmount.isBlank()) {

                String previous = merged.get(
                        "requested_amount"
                ).asString();

                if (!previous.isBlank()
                        && !sameValue(previous, tableAmount)) {
                    uncertainFields.add("requested_amount");
                }

                merged.put("requested_amount", tableAmount);

            } else if (merged.get("requested_amount")
                    .asString().isBlank()) {

                String context = amountContext(content.text());

                if (!context.isBlank()
                        || !content.images().isEmpty()) {

                    String amount = content.images().isEmpty()
                            ? client.extractRequestedAmount(context)
                            : client.extractRequestedAmount(
                            context,
                            content.images()
                    );

                    if (!amount.isBlank()) {
                        merged.put("requested_amount", amount);
                    }
                }
            }
        }

        ArrayNode uncertain = merged.putArray(
                AiDocumentContract.UNCERTAIN_FIELDS
        );

        for (String field : uncertainFields) {
            uncertain.add(field);
        }

        return JSON.writeValueAsString(merged);
    }

    static String requestedAmountFromTable(String text) {

        if (text == null || text.isBlank()) {
            return "";
        }

        String[] pages = text.split(
                "(?=\\[Страница \\d+(?:\\]|:))"
        );

        String selected = "";

        for (String page : pages) {

            String normalized = page
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .replaceAll("(?m)^\\h*\\n", "");

            String lower = normalized.toLowerCase(Locale.ROOT);

            int cofinancing = lower.indexOf("софинансирован");
            int requested = lower.indexOf("запрашиваемая сумма");

            if (cofinancing < 0
                    || requested < 0
                    || Math.abs(cofinancing - requested) > 500) {
                continue;
            }

            String candidate = findTotal(
                    normalized,
                    requested,
                    cofinancing,
                    TOTAL_INLINE,
                    TOTAL_AFTER
            );

            if (candidate.isBlank()) {
                candidate = findTotal(
                        normalized,
                        requested,
                        cofinancing,
                        TOTAL_BEFORE
                );
            }

            if (candidate.isBlank()) {
                continue;
            }

            if (selected.isBlank()) {
                selected = candidate;
            } else if (!sameValue(selected, candidate)) {
                return "";
            }
        }

        return selected;
    }

    private static String findTotal(
            String text,
            int requested,
            int cofinancing,
            Pattern... patterns
    ) {
        String result = "";
        int headerEnd = Math.max(requested, cofinancing);

        for (Pattern pattern : patterns) {

            Matcher matcher = pattern.matcher(text);

            while (matcher.find()) {

                if (matcher.start() <= headerEnd) {
                    continue;
                }

                String first = matcher.group(1).strip();
                String second = matcher.group(2).strip();

                if (DocumentFieldChecks.parseAmount(first).isEmpty()
                        || DocumentFieldChecks.parseAmount(second).isEmpty()) {
                    continue;
                }

                String candidate = requested < cofinancing
                        ? first
                        : second;

                if (result.isBlank()) {
                    result = candidate;
                } else if (!sameValue(result, candidate)) {
                    return "";
                }
            }
        }

        return result;
    }

    private static boolean sameValue(
            String first,
            String second
    ) {
        var firstAmount = DocumentFieldChecks.parseAmount(first);
        var secondAmount = DocumentFieldChecks.parseAmount(second);

        if (firstAmount.isPresent() && secondAmount.isPresent()) {
            return firstAmount.get().compareTo(
                    secondAmount.get()
            ) == 0;
        }

        return first.equals(second);
    }

    static String classificationExcerpt(String text) {

        if (text == null || text.isBlank()) {
            return "";
        }

        int nextPage = text.indexOf("[Страница 2]");

        int end = nextPage > 0
                ? Math.min(nextPage, CHUNK_SIZE)
                : Math.min(text.length(), CHUNK_SIZE);

        return text.substring(0, end).strip();
    }

    static String amountContext(String text) {

        if (text == null || text.isBlank()) {
            return "";
        }

        String lower = text.toLowerCase(Locale.ROOT);

        String[] labels = {
                "запрашиваемая сумма",
                "запрашиваемый размер",
                "сумма субсидии",
                "размер субсидии"
        };

        int position = -1;

        for (String label : labels) {

            int found = lower.lastIndexOf(label);

            if (found > position) {
                position = found;
            }
        }

        if (position < 0) {
            return "";
        }

        int start = Math.max(0, position - 800);
        int end = Math.min(
                text.length(),
                position + 3500
        );

        return text.substring(start, end).strip();
    }

    static List<String> splitText(String text) {

        if (text == null || text.isBlank()) {
            return List.of("");
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {

            int end = Math.min(
                    start + CHUNK_SIZE,
                    text.length()
            );

            if (end < text.length()) {

                int newline = text.lastIndexOf('\n', end);

                if (newline > start + CHUNK_SIZE / 2) {
                    end = newline + 1;
                }
            }

            String chunk = text.substring(start, end).strip();

            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            start = end;
        }

        return chunks;
    }
}
