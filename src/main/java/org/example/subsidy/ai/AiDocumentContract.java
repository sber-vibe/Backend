
package org.example.subsidy.ai;

import java.util.List;
import java.util.stream.Stream;

final class AiDocumentContract {

    static final String DOCUMENT_TYPE = "document_type";
    static final String UNCERTAIN_FIELDS = "uncertain_fields";

    static final String APPLICATION = "APPLICATION";
    static final String UNKNOWN = "UNKNOWN";

    static final List<String> DOCUMENT_TYPES = List.of(
            APPLICATION,
            UNKNOWN
    );

    static final List<String> DATA_FIELDS = List.of(
            "organization",
            "inn",
            "kpp",
            "bank_inn",
            "bank_kpp",
            "document_date",
            "requested_amount",
            "purpose"
    );

    static final List<String> UNCERTAIN_FIELD_NAMES =
            Stream.concat(
                    Stream.of(DOCUMENT_TYPE),
                    DATA_FIELDS.stream()
            ).toList();

    static final List<String> RAW_FIELDS =
            Stream.concat(
                    UNCERTAIN_FIELD_NAMES.stream(),
                    Stream.of(UNCERTAIN_FIELDS)
            ).toList();

    private AiDocumentContract() {
    }
}
