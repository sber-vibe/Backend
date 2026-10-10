
package org.example.subsidy.ai;

import java.util.List;
import java.util.Map;

final class AiDocumentContract {

    static final String DOCUMENT_TYPE = "document_type";
    static final String UNCERTAIN_FIELDS = "uncertain_fields";
    static final String ACCOUNTS = "accounts";
    static final String PASSPORT_DETAILS = "passport_details";

    static final String APPLICATION = "APPLICATION";
    static final String PERSONAL_DATA_CONSENT = "PERSONAL_DATA_CONSENT";
    static final String CONFORMITY_CERTIFICATE = "CONFORMITY_CERTIFICATE";
    static final String PROJECT_PRESENTATION = "PROJECT_PRESENTATION";
    static final String COST_JUSTIFICATION = "COST_JUSTIFICATION";
    static final String BANK_STATEMENT = "BANK_STATEMENT";
    static final String REAL_ESTATE_DOCUMENT = "REAL_ESTATE_DOCUMENT";
    static final String GUARANTEE_LETTER = "GUARANTEE_LETTER";
    static final String UNKNOWN = "UNKNOWN";

    static final List<String> PASSPORT_FIELDS = List.of(
            "passport_series",
            "passport_number",
            "passport_issued_by",
            "passport_issue_date"
    );

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
                    "full_name",
                    "passport_series",
                    "passport_number",
                    "passport_issued_by",
                    "passport_issue_date",
                    "document_date"
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
                    "organization", "inn",
                    "passport_series",
                    "passport_number",
                    "passport_issued_by",
                    "passport_issue_date",
                    "document_date", ACCOUNTS
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
            PROJECT_PRESENTATION, List.of("project_name"),
            COST_JUSTIFICATION, List.of(
                    "total_expenses", "own_funds"
            ),
            BANK_STATEMENT, List.of(
                    "organization", "document_date", ACCOUNTS
            ),
            REAL_ESTATE_DOCUMENT, List.of("cadastral_number"),
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
                    organization: организация или ИП заявителя.
                    inn: ИНН заявителя.
                    ogrn: ОГРН или ОГРНИП.
                    kpp: КПП заявителя, если применимо.
                    document_date: дата заявления.
                    requested_amount: запрашиваемая сумма субсидии.
                    signatory: ФИО подписанта.
                    purpose: цель проекта или субсидии.
                    """;

            case PERSONAL_DATA_CONSENT -> """
                    full_name: ФИО субъекта персональных данных.
                    document_date: дата подписания согласия.

                    passport_series: серия паспорта субъекта,
                    ровно четыре цифры без пробелов.

                    passport_number: номер паспорта субъекта,
                    ровно шесть цифр без пробелов.

                    passport_issued_by: орган, выдавший паспорт.
                    passport_issue_date: дата выдачи паспорта.

                    Извлекай паспортные реквизиты только тогда,
                    когда они явно относятся к субъекту согласия.
                    Не принимай паспорт за ИНН.
                    """;

            case CONFORMITY_CERTIFICATE -> """
                    certificate_number: номер сертификата.
                    certification_body: орган сертификации.
                    issue_date: дата выдачи.
                    valid_until: дата окончания действия.
                    """;

            case PROJECT_PRESENTATION -> """
                    project_name: название проекта.
                    project_description: краткое описание проекта.
                    """;

            case COST_JUSTIFICATION -> """
                    total_expenses: общая сумма расходов.
                    own_funds: сумма собственных средств.
                    expense_items: основные статьи расходов.
                    Не подменяй собственные средства субсидией.
                    """;

            case BANK_STATEMENT -> """
                    organization: владелец счетов, а не банк.
                    inn: только явно обозначенный ИНН клиента.
                    document_date: дата банковской справки.

                    passport_series: серия паспорта клиента,
                    четыре цифры.

                    passport_number: номер паспорта клиента,
                    шесть цифр.

                    passport_issued_by: орган выдачи паспорта клиента.
                    passport_issue_date: дата выдачи паспорта клиента.

                    Если в справке указан паспорт вида
                    "6015 865696", отдели серию от номера.
                    Это пример формата, а не значение для ответа.

                    Не записывай десять цифр паспорта в inn.
                    Не используй ИНН банка в качестве ИНН клиента.

                    accounts: массив всех отдельных счетов.

                    Для каждого элемента accounts:
                    account_number: номер соответствующего счёта.
                    account_balance: остаток на этом счёте.

                    Не объединяй остатки разных счетов.
                    Если связь номера и остатка сомнительна,
                    добавь accounts в uncertain_fields.
                    """;

            case REAL_ESTATE_DOCUMENT -> """
                    cadastral_number: кадастровый номер объекта.
                    area: площадь.
                    property_purpose: назначение объекта.
                    address: адрес недвижимости.
                    rights_holder: правообладатель.
                    Не записывай Роскадастр в правообладатели.
                    """;

            case GUARANTEE_LETTER -> """
                    organization: организация или ИП-гарант.
                    inn: ИНН гаранта, если указан.
                    guarantee_amount: конкретная сумма гарантии.
                    document_date: дата гарантийного письма.
                    signatory: подписант письма.
                    """;

            default -> "";
        };
    }

    private AiDocumentContract() {
    }
}
