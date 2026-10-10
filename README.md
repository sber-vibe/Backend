# Backend — проверка пакета документов на субсидию

Заготовка для кейса «Агентство инноваций» на хакатоне Сбера. Java, Spring Boot 4.1.1, Gradle, REST API.

## Запуск

Для сборки используйте JDK 26 (совместим с Gradle 9.6 из проекта). Укажите `JAVA_HOME` на ваш JDK.

```powershell
.\gradlew.bat test bootJar
.\gradlew.bat bootRun
```

Linux/macOS: `sh ./gradlew test bootJar`, затем `sh ./gradlew bootRun`.
Сервис: http://localhost:8080. Health: `GET /actuator/health`.
Порт задаётся переменной окружения `PORT`. Собранный JAR: `build/libs/backend.jar`.

```sh
docker build -t subsidy-backend .
docker run --rm -p 8080:8080 subsidy-backend
```

## Авторизация (Keycloak)

Если задан JWKS, `/api/**` принимает только JWT от Keycloak (`Authorization: Bearer …`) с realm-ролью `specialist` или `admin`:

| Переменная | Пример |
|---|---|
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` | `http://keycloak:8080/kc/realms/subsidy/protocol/openid-connect/certs` |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | `http://localhost/kc/realms/subsidy` — issuer из токена (публичный адрес) |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | `subsidy-backend` |

Проверяются подпись, срок действия, issuer и audience. Без токена — `401`, без нужной роли — `403`, оба в формате Problem Details.
`/actuator/health` и `/actuator/info` открыты. Если JWKS не задан, API открыт (демо-режим, как раньше).

## API

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/api/v1/document-types` | Обязательные типы документов демо |
| POST | `/api/v1/applications` | Создать заявку |
| GET | `/api/v1/applications` | Список заявок |
| GET | `/api/v1/applications/{id}` | Заявка, документы и последний отчёт |
| POST | `/api/v1/applications/{id}/documents` | Multipart: поля `type` и `file` |
| GET | `/api/v1/applications/{id}/documents/{documentId}` | Скачать документ |
| DELETE | `/api/v1/applications/{id}/documents/{documentId}` | Удалить документ |
| POST | `/api/v1/applications/{id}/check` | Проверить комплектность |

Создание заявки:

```json
{
  "applicantName": "ООО Инновации",
  "inn": "6161000000",
  "projectName": "Новый продукт",
  "requestedAmount": 100000
}
```

Пример сценария (bash; в PowerShell вызывайте `curl.exe`):

```sh
curl -X POST http://localhost:8080/api/v1/applications \
  -H 'Content-Type: application/json' \
  -d '{"applicantName":"Demo","inn":"6161000000","projectName":"Innovation","requestedAmount":100000}'
curl -X POST http://localhost:8080/api/v1/applications/UUID/documents \
  -F 'type=APPLICATION' -F 'file=@application.pdf'
curl -X POST http://localhost:8080/api/v1/applications/UUID/check
```

Замените `UUID` на `id` созданной заявки. Типы: `APPLICATION`, `REGISTRATION_EXTRACT`,
`COST_ESTIMATE` (обязательные), `SUPPORTING_DOCUMENT` (дополнительный).

Ошибки возвращаются в формате Problem Details (`application/problem+json`).
ИНН проверяется только по длине и цифрам, сумма должна быть положительной.
Файл: до 10 MiB; форматы по расширению: PDF, DOCX, TXT, PNG, JPG, JPEG.
Фактическое содержимое и MIME-тип не проверяются. Скачивание — как двоичный attachment.
До 100 заявок, 20 документов на заявку и 50 MiB файлов суммарно для одного процесса.

## Границы заготовки

Перечень документов демонстрационный, а не официальный регламент субсидии.
Проверка выявляет недостающие типы и дубликаты. Неполный пакет получает `INCOMPLETE`,
полный — `REVIEW_REQUIRED`. Решение об одобрении сервис не принимает.
После загрузки или удаления документа предыдущий отчёт сбрасывается.

Данные и файлы хранятся в памяти и теряются при перезапуске. Авторизация — только при заданном Keycloak;
для демо используйте синтетические документы. OCR, извлечение реквизитов, AI-анализ,
проверка подписей, PostgreSQL/S3 и роли пользователей пока не реализованы.
Для продолжения: уточнить регламент, заменить хранилище и вынести анализ документов
в отдельный сервис; затем добавить авторизацию и интеграцию с фронтендом.

GitHub Actions запускает тесты и сборку на push/PR.
