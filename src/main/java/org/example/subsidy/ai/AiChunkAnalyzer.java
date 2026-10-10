
package org.example.subsidy.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AiChunkAnalyzer {

    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    private static final int CHUNK_SIZE = 4000;
    private static final int MAX_CHUNKS = 40;

    private static final Set<String> AMOUNT_FIELDS = Set.of(
            "requested_amount", "total_expenses",
            "own_funds", "guarantee_amount"
    );

    private static final Set<String> DATE_FIELDS = Set.of(
            "document_date", "issue_date", "valid_until"
    );

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
        String text = content.text() == null
                ? "" : content.text();

        List<String> chunks = splitText(text);

        if (chunks.size() > MAX_CHUNKS) {
            throw new IllegalStateException(
                    "Document contains too many chunks"
            );
        }

        List<byte[]> primaryImages =
                text.isBlank()
                        || text.contains(": изображение №")
                        ? content.images()
                        : List.of();

        String type = client.classifyDocument(
                classificationExcerpt(text),
                primaryImages
        );

        if (!AiDocumentContract.DOCUMENT_TYPES.contains(type)) {
            throw new IllegalStateException(
                    "Invalid document type"
            );
        }

        List<String> fields =
                AiDocumentContract.fieldsFor(type);

        Map<String, List<Observation>> observations =
                new LinkedHashMap<>();

        for (String field : fields) {
            if (!field.equals(AiDocumentContract.ACCOUNTS)) {
                observations.put(field, new ArrayList<>());
            }
        }

        ArrayNode accounts = JSON.createArrayNode();

        Set<String> missingUncertain =
                new LinkedHashSet<>();

        boolean accountsUncertain = false;

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

                JsonNode uncertainNode = response.get(
                        AiDocumentContract.UNCERTAIN_FIELDS
                );

                if (uncertainNode == null
                        || !uncertainNode.isArray()) {
                    throw new IllegalStateException(
                            "Invalid uncertain fields"
                    );
                }

                Set<String> chunkUncertain =
                        new LinkedHashSet<>();

                for (JsonNode item : uncertainNode) {

                    if (!item.isString()
                            || !fields.contains(item.asString())) {
                        throw new IllegalStateException(
                                "Invalid uncertain field name"
                        );
                    }

                    chunkUncertain.add(item.asString());
                }

                for (String field : fields) {

                    if (field.equals(AiDocumentContract.ACCOUNTS)) {

                        JsonNode rawAccounts = response.get(field);

                        if (rawAccounts == null
                                || !rawAccounts.isArray()
                                || rawAccounts.size() > 30) {
                            throw new IllegalStateException(
                                    "Invalid accounts array"
                            );
                        }

                        for (JsonNode account : rawAccounts) {

                            if (!validAccount(account)) {
                                throw new IllegalStateException(
                                        "Invalid bank account entry"
                                );
                            }

                            if (mergeAccount(accounts, account)) {
                                accountsUncertain = true;
                            }
                        }

                        if (chunkUncertain.contains(field)) {
                            accountsUncertain = true;
                        }

                        continue;
                    }

                    JsonNode node = response.get(field);

                    if (node == null || !node.isString()) {
                        throw new IllegalStateException(
                                "Invalid extraction field: " + field
                        );
                    }

                    String value = node.asString().strip();

                    if (value.isEmpty()) {

                        if (chunkUncertain.contains(field)) {
                            missingUncertain.add(field);
                        }

                    } else {

                        observations.get(field).add(
                                new Observation(
                                        value,
                                        chunkUncertain.contains(field)
                                )
                        );
                    }
                }
            }
        }

        ObjectNode merged = JSON.createObjectNode();

        merged.put(AiDocumentContract.DOCUMENT_TYPE, type);

        Set<String> uncertainFields =
                new LinkedHashSet<>();

        for (String field : fields) {

            if (field.equals(AiDocumentContract.ACCOUNTS)) {

                merged.set(field, accounts);

                if (accountsUncertain) {
                    uncertainFields.add(field);
                }

                continue;
            }

            List<Variant> variants = new ArrayList<>();

            for (Observation observation :
                    observations.get(field)) {

                Variant matched = null;

                for (Variant variant : variants) {

                    if (sameValue(
                            field,
                            variant.value,
                            observation.value()
                    )) {
                        matched = variant;
                        break;
                    }
                }

                if (matched == null) {
                    matched = new Variant(observation.value());
                    variants.add(matched);
                }

                matched.total++;

                if (!observation.uncertain()) {
                    matched.confident++;
                }
            }

            if (variants.isEmpty()) {

                merged.put(field, "");

                if (missingUncertain.contains(field)
                        && AiDocumentContract.requiredFor(type)
                        .contains(field)) {
                    uncertainFields.add(field);
                }

                continue;
            }

            Variant best = variants.get(0);

            for (Variant variant : variants) {

                if (variant.confident > best.confident
                        || (variant.confident == best.confident
                        && variant.total > best.total)) {
                    best = variant;
                }
            }

            merged.put(field, best.value);

            if (variants.size() > 1
                    || best.confident == 0) {
                uncertainFields.add(field);
            }
        }

        if (type.equals(AiDocumentContract.APPLICATION)) {

            String tableAmount = requestedAmountFromTable(text);

            if (!tableAmount.isBlank()) {

                boolean conflict = observations
                        .get("requested_amount")
                        .stream()
                        .anyMatch(observation -> !sameValue(
                                "requested_amount",
                                observation.value(),
                                tableAmount
                        ));

                merged.put("requested_amount", tableAmount);

                if (conflict) {
                    uncertainFields.add("requested_amount");
                } else {
                    uncertainFields.remove("requested_amount");
                }

            } else if (merged.get("requested_amount")
                    .asString().isBlank()) {

                String context = amountContext(text);

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

    private static boolean validAccount(JsonNode account) {

        return account != null
                && account.isObject()
                && account.size() == 2
                && account.has("account_number")
                && account.get("account_number").isString()
                && account.has("account_balance")
                && account.get("account_balance").isString();
    }

    private static boolean mergeAccount(
            ArrayNode accounts,
            JsonNode account
    ) {
        String number = account.get(
                "account_number"
        ).asString().strip();

        String balance = account.get(
                "account_balance"
        ).asString().strip();

        if (number.isEmpty() && balance.isEmpty()) {
            return false;
        }

        if (!number.isEmpty()) {

            for (JsonNode existing : accounts) {

                String existingNumber = existing.get(
                        "account_number"
                ).asString();

                if (!number.equals(existingNumber)) {
                    continue;
                }

                String existingBalance = existing.get(
                        "account_balance"
                ).asString();

                if (existingBalance.isEmpty()
                        && !balance.isEmpty()) {

                    ((ObjectNode) existing).put(
                            "account_balance",
                            balance
                    );

                    return false;
                }

                return !existingBalance.isEmpty()
                        && !balance.isEmpty()
                        && !sameValue(
                        "account_balance",
                        existingBalance,
                        balance
                );
            }
        }

        ObjectNode result = accounts.addObject();

        result.put("account_number", number);
        result.put("account_balance", balance);

        return number.isEmpty() || balance.isEmpty();
    }

    private record Observation(
            String value,
            boolean uncertain
    ) {
    }

    private static final class Variant {

        private final String value;
        private int total;
        private int confident;

        private Variant(String value) {
            this.value = value;
        }
    }

    private static boolean sameValue(
            String field,
            String first,
            String second
    ) {
        if (AMOUNT_FIELDS.contains(field)
                || field.equals("account_balance")) {

            var left = DocumentFieldChecks.parseAmount(first);
            var right = DocumentFieldChecks.parseAmount(second);

            if (left.isPresent() && right.isPresent()) {
                return left.get().compareTo(right.get()) == 0;
            }
        }

        if (DATE_FIELDS.contains(field)) {

            var left = DocumentFieldChecks.parseDate(first);
            var right = DocumentFieldChecks.parseDate(second);

            if (left.isPresent() && right.isPresent()) {
                return left.get().isEqual(right.get());
            }
        }

        return first.strip()
                .replaceAll("\\s+", " ")
                .equalsIgnoreCase(
                        second.strip().replaceAll("\\s+", " ")
                );
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

            String lower =
                    normalized.toLowerCase(Locale.ROOT);

            int cofinancing =
                    lower.indexOf("софинансирован");

            int requested =
                    lower.indexOf("запрашиваемая сумма");

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
            } else if (!sameValue(
                    "requested_amount",
                    selected,
                    candidate
            )) {
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

        int headerEnd =
                Math.max(requested, cofinancing);

        for (Pattern pattern : patterns) {

            Matcher matcher = pattern.matcher(text);

            while (matcher.find()) {

                if (matcher.start() <= headerEnd) {
                    continue;
                }

                String first = matcher.group(1).strip();
                String second = matcher.group(2).strip();

                if (DocumentFieldChecks.parseAmount(first)
                        .isEmpty()
                        || DocumentFieldChecks.parseAmount(second)
                        .isEmpty()) {
                    continue;
                }

                String candidate = requested < cofinancing
                        ? first : second;

                if (result.isBlank()) {
                    result = candidate;
                } else if (!sameValue(
                        "requested_amount",
                        result,
                        candidate
                )) {
                    return "";
                }
            }
        }

        return result;
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
            position = Math.max(
                    position,
                    lower.lastIndexOf(label)
            );
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

            String chunk = text.substring(
                    start, end
            ).strip();

            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            start = end;
        }

        return chunks;
    }
}
