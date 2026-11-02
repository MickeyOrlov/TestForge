# module-quality в бою: spring-petclinic-rest

Ноябрь 2026. Модуль качества прогнан на чужом open-source приложении так,
как его подключил бы адаптирующий проект: TestForge — из опубликованных
артефактов (`publishTestForgeLibraries`), без доступа к исходникам.

## Стенд

| Что | Как |
|---|---|
| Приложение | [spring-petclinic-rest](https://github.com/spring-petclinic/spring-petclinic-rest) `d350090`: Spring Boot 4.1, OpenAPI 3 (`openapi.yml`), H2, 244 собственных теста (Maven Surefire), JaCoCo |
| Мутации | PIT 1.30.1 + pitest-junit5-plugin 1.2.3: `model.*`, `rest.validation.*`, `ClinicServiceImpl` против трёх тестовых классов (Spring-тесты медленные под мутациями) |
| Diff coverage | diff-cover 11.0.0 по `jacoco.xml` против `origin/master` |
| Стенд TestForge | отдельный Gradle-проект: 8 функциональных тестов через `ApiClient` с contract guard (`record`), explorer по всем GET, reporting в run index; шаг `qualityGate` судит всё вместе |
| Состояние между прогонами | `quality-state/`: baseline и история (как CI-кэш) |

Конфигурация гейта: `fail-on: [functional, stability, contracts, reliability]`,
`required: [functional, contracts]`, `min-tests: 50`.

## Прогоны

| # | Сценарий | Гейт | Что показал |
|---|---|---|---|
| r0 | Чистый, без baseline | FAIL | 2 реальных расхождения с контрактом и 500 на `/oops` — см. ниже |
| r1 | `writeBaseline()` | — | baseline записан явным шагом |
| r2 | Тот же код | PASS | известные проблемы больше не валят гейт |
| r3 | Тот же код, тест красный (`DOGFOOD_FLAKY=1`) | FAIL | новое падение с отпечатком `edd72cea0c2b` |
| r4 | Тот же код, тест снова зелёный | PASS | `stability` WARN: «flaky across runs» на одной ревизии |
| r5 | Ветка с регрессией и новым кодом без тестов | FAIL | собственный тест петклиники `404 ≠ 200`; diff-coverage 1 из 8 строк |
| r6 | Падение в карантине по отпечатку | PASS | `accepted (1)` |
| r7 | Карантин с чужим отпечатком | FAIL | заметка «accepted for fingerprint 000…, failed with ea5b…» |
| r8 | CODEOWNERS в репозитории петклиники | — | тест → `@petclinic/qa`, мутанты и diff → `@petclinic/model` / `core` |

## Что модуль может — подтверждено на реальном проекте

- **Сводит чужие и свои результаты.** 244 теста Maven Surefire петклиники и
  8 тестов стенда на Gradle читаются как один прогон (253 с flaky-тестом).
- **Находит реальные дефекты контракта.** Contract guard на 26 ответах:
  - `GET /oops` отвечает 500, которого нет в документе (`UNDOCUMENTED_STATUS`);
  - `GET /owners/{id}` для несуществующего владельца отдаёт 404 без
    `Content-Type`, хотя springdoc обещает тело (`UNEXPECTED_CONTENT_TYPE`) —
    ровно тот случай, что описан в README explorer.
- **Reliability не верит тесту.** `/oops` — 500, тест ожидает 500, измерение
  всё равно FAIL: серверная ошибка — дефект.
- **Покрытие API честное.** 46 %: 21 записывающая операция без единого теста,
  списком.
- **PIT читается как есть.** Настоящий `mutations.xml` (атрибуты в одинарных
  кавычках) — 105 мутантов, 6 выживших, сила тестов 92 % на покрытом коде.
- **diff-cover читается в обоих форматах.** И устаревший `--json-report`, и
  новый `--format json:<file>` дают одну структуру. Новый метод без тестов —
  `Owner.java lines 141, 143-148`.
- **Baseline, flaky, отпечатки, карантин работают как задумано**, включая
  отказ при неверном отпечатке.
- **Efficiency** после трёх прогонов сравнивает 252 теста с их медианами и
  делит на темп машины (×1,09).
- **CODEOWNERS** на настоящей раскладке `src/main|test/java` находит файлы
  тестов и мутированных классов.
- **Несколько Spring-контекстов** в одном JVM пишут `contract-guard.json` и
  `contract-guard-2.json` — оба прочитаны.

## Что модуль не может — ограничения по устройству

- **Видит только трафик через TestForge.** 244 MockMvc-теста петклиники
  попадают в `functional`, но их HTTP-вызовы не доходят до `contracts`,
  `reliability`, `api-coverage`. Contract guard работает только на `ApiClient`.
- **Чёрный ящик без БД.** Приложение — отдельный процесс: `db-queries`,
  `data`, остатки данных в `hygiene` — NOT MEASURED. Нужен доступ модулей
  `module-db` / `module-db-contract` к базе приложения.
- **Не запускались:** fuzz (`robustness`), security, матрица доступа
  (`authorization`), `flow-coverage` — это отдельные модули, в стенде их нет.
- **Продакшен-сигналы** (escape rate, CFR) — вне модели, как и ожидалось.
- **Первые прогоны без истории.** `efficiency` по медианам — с третьего
  прогона; до этого — baseline или NOT MEASURED.
- **PIT на Spring-приложении медленный**; выбор целей мутаций (`targetClasses`
  по diff) остаётся проекту — модуль только читает отчёт.

## Что нужно доработать — найдено в бою

| # | Проблема | Где видно | Предложение | Размер |
|---|---|---|---|---|
| D1 | **Одна операция — три ключа.** `GET /petclinic/api/owners/{n}` (reliability), `api GET /owners/{ownerId}` (contracts), `petclinic GET /owners/{ownerId}` (api-coverage). Компоненты и CODEOWNERS не сводят их, одна проблема не видна как одна | r8: 23 issue в `(unassigned)` | общий формат ключа операции `<spec> <METHOD> <template>`; `reliability` сопоставляет путь с документом, как contract guard | M |
| D2 | **Сводка говорит FAIL при зелёном гейте.** После baseline `quality-summary.md` начинается «Quality: FAIL», хотя гейт прошёл | r2, r6 | строка вердикта гейта (`gate: PASS — 0 new issues in fail-on`) в `quality.md`, сводке и `quality.json` | S |
| D3 | **Балл 100 при FAIL.** 252 из 253 → 99,6 % → округляется в 100; тренд `100 → 100` при упавшем тесте | r3, r5 | балл не может быть 100, пока есть хоть одна проблема (округление вниз) | S |
| D4 | **Стухшие отчёты PIT и diff-cover читаются молча.** У JUnit есть `results-not-before`, у них — нет; отчёт с прошлой ревизии судился бы как текущий. Их нет и в списке «Inputs» | r5 | файлы отчётов во «Inputs» с временем изменения; отчёт старше `results-not-before` — NOT MEASURED с причиной | S |
| D5 | **Не видно, какие измерения решают гейт.** `diff-coverage` FAIL вне `fail-on` ничего не роняет, и `dry-run` на нём молчит — читатель не поймёт, почему FAIL «не считается» | r5 | колонка «Gate» в таблице (`enforced` / `dry-run` / `report only`) | S |
| D6 | **Подсказки не по ситуации.** У `functional` всегда фраза про `min-tests`, даже когда тестов достаточно | r3–r7 | подсказка из частей по фактам: про `min-tests` — только при нехватке | S |
| D7 | **Неинформативные факты.** diff-cover пишет `report_name: "XML"` → «(XML)»; сообщение гейта берёт первый факт («26 API responses checked…») вместо числа расхождений | r0, r5 | не печатать `XML`; в сообщении гейта — факт о проблеме, не о выборке | S |
| D8 | **README советует устаревшую опцию.** diff-cover 11 предупреждает: `--json-report` устарел, нужен `--format json:<file>` | r0 | обновить пример | S |

D1 — самое важное: без него владельцы, компоненты и подсчёт «одна проблема
в нескольких измерениях» на реальном API не работают. D2–D8 — полировка
отчёта, каждая на час-два.

**Статус:** D1, D2, D3, D4, D8 и часть D7 (`(XML)`) исправлены вместе с CLI
`testforge-quality` и чтением WFC; повторный прогон CLI на данных петклиники
с отчётом EvoMaster: 81 группа находок, `reliability` → `petclinic GET /oops`.
D5 (колонка Gate: `enforced` / `dry-run` / `report only`), D6 (подсказка про
`min-tests` только при нехватке тестов) и остаток D7 (в сообщении гейта
сначала новые проблемы) закрыты следующим коммитом — все восемь находок
исправлены.

## Не велосипед ли

Проверено после прогонов (поиск, ноябрь 2026):

- **Обход приложения — готов и сильнее нашего.** EvoMaster (LGPL, v6.2.0,
  сентябрь 2026) по OpenAPI сам строит цепочки запросов, ищет 500,
  расхождения со схемой и нарушения доступа и генерирует JUnit-тесты;
  Schemathesis (уже в `module-api-fuzz`), RESTler, ZAP. `module-api-explorer`
  обходит только GET — частичный велосипед. Решение: обходит внешний
  инструмент, его результаты — входы модуля качества.

  **Прогон EvoMaster 6.2.0 против петклиники** (чёрный ящик, 3 минуты,
  `--bbSwaggerUrl` на `openapi.yml`):

  | | `module-api-explorer` (только GET) | EvoMaster |
  |---|---|---|
  | HTTP-вызовов | 26 (с функциональными тестами) | 1879 |
  | Операций с 2xx | 15 из 37 | 16 из 37 |
  | Операций с 500 | 1 (`/oops`) | 41, в т. ч. `DELETE /owners/{id}`, `DELETE /pets/{id}`, `GET /v2/owners`, `GET /v2/pets`, `OPTIONS` на большинстве путей |
  | Операций с нарушением схемы ответа | 2 | 36 (content type, `allOf`, пустое тело, неописанный статус) |
  | Тесты на находки | — | 134 JUnit 5 (`EvoMaster_faults_Test.java`, `…_successes_Test.java`) |

  Записывающие запросы EvoMaster меняют данные приложения (у петклиники H2 в
  памяти — сбрасывается перезапуском); против общего стенда так нельзя.

  Результат он пишет в `report.json` в формате
  [Web Fuzzing Commons](https://arxiv.org/pdf/2509.01612) (`schemaVersion`
  0.7.0): `faults.foundFaults[]` с `operationId` (`POST:/petclinic/api/owners`),
  `testCaseId` и `faultCategories[].code` (100 — HTTP 500, 200 — нарушение
  схемы ответа с `context`, ещё 120 и 301 — расшифровать по спецификации WFC),
  плюс `problemDetails.rest` с покрытыми эндпоинтами и статусами. Это готовый
  вход для модуля качества — `robustness` может читать WFC-отчёт так же, как
  сейчас `findings.json` из `module-api-fuzz`. И это ещё один формат ключа
  операции (`METHOD:/full/path`) — довод за D1.
- **Судья по разнородным отчётам — частично есть.**
  [uhafner/quality-monitor](https://github.com/uhafner/quality-monitor) (MIT,
  активен; автор Jenkins warnings-ng и coverage) читает JUnit, JaCoCo, PIT и
  100+ форматов статанализа, держит пороги и комментирует PR — но это
  GitHub/GitLab Action, без baseline «новое против известного», карантина,
  истории flaky и без рантайм-сигналов (контракты, 5xx, покрытие API).
  [Allure 3](https://allurereport.org/docs/quality-gate/) — `allure quality-gate`
  с `maxFailures`, `minTestsCount`, `successRate`, только по своим результатам и
  несовместим с ретраями. Jenkins Coverage plugin — baseline по изменённым
  файлам, но нужен сервер.
- **Вывод.** Собственная ниша модуля — baseline и карантин по отпечатку,
  история, рантайм-измерения TestForge, NOT MEASURED, работа без сервера.
  Покрытие и мутации у quality-monitor сделаны шире; стоит проверить, не
  читать ли его `metrics.env` / модель как вход вместо своих парсеров.

## Повторить

Стенд лежит вне репозитория (`~/Documents/quality-dogfood`): клон петклиники с
PIT в `pom.xml`, `harness/` и `run.sh <run-id> <revision> [-P…]`, который
гоняет набор стенда против запущенного приложения и затем `qualityGate`.
