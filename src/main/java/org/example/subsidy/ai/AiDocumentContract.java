
package org.example.subsidy.ai;

import java.util.List;
import java.util.Map;

final class AiDocumentContract {

    static final String DOCUMENT_TYPE = "document_type";
    static final String UNCERTAIN_FIELDS = "uncertain_fields";

    static final String APPLICATION = "APPLICATION";
    static final String PERSONAL_DATA_CONSENT = "PERSONAL_DATA_CONSENT";
    static final String CONFORMITY_CERTIFICATE = "CONFORMITY_CERTIFICATE";
    static final String PROJECT_PRESENTATION = "PROJECT_PRESENTATION";
    static final String COST_JUSTIFICATION = "COST_JUSTIFICATION";
    static final String BANK_STATEMENT = "BANK_STATEMENT";
    static final String REAL_ESTATE_DOCUMENT = "REAL_ESTATE_DOCUMENT";
    static final String GUARANTEE_LETTER = "GUARANTEE_LETTER";
    static final String UNKNOWN = "UNKNOWN";

    static final List<String> DOCUMENT_TYPES = List.of(
            APPLICATION,
            PERSONAL_DATA_CONSENT,
            CONFORMITY_CERTIFICATE,
            PROJECT_PRESENTATION,
            COST_JUSTIFICATION,
            BANK_STATEMENT,
            REAL_ESTATE_DOCUMENT,
            GUARANTEE_LETTER,
            UNKNOWN
    );

    private static final Map<String, List<String>> FIELDS = Map.of(
            APPLICATION, List.of(
                    "organization", "inn", "ogrn", "kpp",
                    "document_date", "requested_amount",
                    "signatory", "purpose"
            ),
            PERSONAL_DATA_CONSENT, List.of(
                    "full_name", "passport_details", "document_date"
            ),
            CONFORMITY_CERTIFICATE, List.of(
                    "certificate_number", "certification_body",
                    "issue_date", "valid_until"
            ),
            PROJECT_PRESENTATION, List.of(
                    "project_name", "project_description"
            ),
            COST_JUSTIFICATION, List.of(
                    "total_expenses", "own_funds", "expense_items"
            ),
            BANK_STATEMENT, List.of(
                    "organization", "inn", "account_number",
                    "account_balance", "document_date"
            ),
            REAL_ESTATE_DOCUMENT, List.of(
                    "cadastral_number", "area",
                    "property_purpose", "address", "rights_holder"
            ),
            GUARANTEE_LETTER, List.of(
                    "organization", "inn", "guarantee_amount",
                    "document_date", "signatory"
            )
    );

    private static final Map<String, List<String>> REQUIRED = Map.of(
            APPLICATION, List.of(
                    "organization", "inn",
                    "requested_amount", "document_date"
            ),
            PERSONAL_DATA_CONSENT, List.of(
                    "full_name", "document_date"
            ),
            CONFORMITY_CERTIFICATE, List.of(
                    "certificate_number", "valid_until"
            ),
            PROJECT_PRESENTATION, List.of(
                    "project_name"
            ),
            COST_JUSTIFICATION, List.of(
                    "total_expenses", "own_funds"
            ),
            BANK_STATEMENT, List.of(
                    "organization", "account_balance", "document_date"
            ),
            REAL_ESTATE_DOCUMENT, List.of(
                    "cadastral_number"
            ),
            GUARANTEE_LETTER, List.of(
                    "organization", "guarantee_amount", "document_date"
            )
    );

    static List<String> fieldsFor(String type) {
        return FIELDS.getOrDefault(type, List.of());
    }

    static List<String> requiredFor(String type) {
        return REQUIRED.getOrDefault(type, List.of());
    }

    static String instructionsFor(String type) {
        return switch (type) {
            case APPLICATION -> """
                    organization: заявитель, организация или ИП.
                    inn: ИНН заявителя.
                    ogrn: ОГРН или ОГРНИП заявителя.
                    kpp: КПП заявителя, если применимо.
                    document_date: дата заявления.
                    requested_amount: запрашиваемая сумма субсидии.
                    signatory: ФИО подписанта.
                    purpose: цель проекта или субсидии.
                    """;

            case PERSONAL_DATA_CONSENT -> """
                    full_name: ФИО субъекта персональных данных.
                    passport_details: паспортные данные субъекта.
                    document_date: дата подписания согласия.
                    """;

            case CONFORMITY_CERTIFICATE -> """
                    certificate_number: номер сертификата.
                    certification_body: орган сертификации.
                    issue_date: дата выдачи сертификата.
                    valid_until: дата окончания срока действия.
                    """;

            case PROJECT_PRESENTATION -> """
                    project_name: наименование проекта.
                    project_description: краткое описание проекта.
                    """;

            case COST_JUSTIFICATION -> """
                    total_expenses: общая сумма расходов.
                    own_funds: сумма собственных средств.
                    expense_items: статьи расходов кратким текстом.
                    Не подменяй собственные средства
                    запрашиваемой субсидией.
                    """;

            case BANK_STATEMENT -> """
                    organization: владелец счёта, не название банка.
                    inn: ИНН владельца счёта, если указан.
                    account_number: номер банковского счёта.
                    account_balance: остаток денежных средств.
                    document_date: дата банковского документа.
                    """;

            case REAL_ESTATE_DOCUMENT -> """
                    cadastral_number: кадастровый номер объекта.
                    area: площадь объекта.
                    property_purpose: назначение объекта.
                    address: адрес объекта недвижимости.
                    rights_holder: правообладатель, если указан.
                    Не записывай Роскадастр в правообладатели.
                    """;

            case GUARANTEE_LETTER -> """
                    organization: организация-гарант.
                    inn: ИНН организации-гаранта, если указан.
                    guarantee_amount: сумма гарантии.
                    document_date: дата гарантийного письма.
                    signatory: подписант гарантийного письма.
                    """;

            default -> "";
        };
    }

    private AiDocumentContract() {
    }
}
