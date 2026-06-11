# TestForge — roadmap

Единый план работ: идеи из архива (clean-room), gap analysis для production v1,
и будущие модули. Статусы обновлять при закрытии этапов.

**Verification (всегда):** `./gradlew build --rerun-tasks` зелёный; новая фича —
example-тест в том же коммите; перед коммитом греп на доменные термины
работодателей (см. [hygiene](#hygiene)).

Связанные документы:

- [production-v1-gap-analysis.md](production-v1-gap-analysis.md) — P0/P1 для
  боевого staging, Testcontainers/Pact
- [AGENTS.md](../AGENTS.md) — playbook адаптации

---

## Статус по этапам

| Этап | Тема | Статус |
|------|------|--------|
| 1 | Embedded-Kafka collector IT | ✅ закоммичен (`b45ab45`) |
| 2 | StateDiff, Flow decorator, Generators | ✅ закоммичен (`0a7edcf`) |
| 3 | module-contract v2 (JSON Schema) | ✅ закоммичен (`4507f0b`, networknt 1.5.8 — 3.x ждёт Jackson 3) |
| 4 | `@Prepared` data pools | ✅ закоммичен (`1f8c4c8`) |
| 5 | Документация и публикация | ✅ доки (`e13f61b`); push отложен — нужен доступ к GitHub |
| 6 | Open Source Readiness & Modernization | ⬜ |
| 7 | UI & Mobile Expansion | 🟡 скелеты playwright/appium (`bca8e25`) |
| — | Production v1 gaps (P0) | ✅ extension, scope helper, staging yml, checklist |

---

## Этап 1 — технический долг (полдня) ✅
... (rest of Stage 1) ...

## Этап 6 — Open Source Readiness & Modernization (2–3 дня) ⬜

**6.1 Modernization (Java 26)**
- [ ] Transition `ScenarioContext` from `ThreadLocal` to `ScopedValue` (Project Loom native)
- [ ] Optimize `Waiter` for virtual threads

**6.2 "AI-First" Positioning**
- [ ] Update README: "The first AI-native Test Automation Template"
- [ ] Add specific "Agent Instructions" sections to each module README
- [ ] Create `docs/adaptation-checklist.md` for both humans and agents

**6.3 Community & Hygiene**
- [ ] Add Open Source badges (License, Java version, CI status)
- [x] Implement `ScenarioContextExtension` for auto-cleanup (move from P0 gaps)
- [x] Integration: JSON Schema behind `MessageContract` (move from Stage 3)

**1.1 Embedded-Kafka тест `KafkaPollingCollector`** (идея: contract monitoring на
брокере)

- [x] `KafkaCollectorIntegrationTest` — `@EmbeddedKafka`, random port, produce →
  `KafkaProbe.awaitMessage`
- [x] Кейс `seek-to-beginning-on-start` после stop/start collector
- [x] Без `@Disabled`, без хардкода портов
- [ ] (опционально) Gradle tag `containers` / отдельный CI job если CI станет
  тяжёлым

Файлы: `example-tests/.../KafkaCollectorIntegrationTest.java`

---

## Этап 2 — идеи из архива (день) ✅

**2.1 `StateDiff` в core** (идея: side-effect assertions в gray-box DB)

- [x] `StateSnapshot` / `StateDiff` в `core/src/main/java/io/testforge/core/diff/`
- [x] `StateDiffTest` — одна новая строка, соседи не тронуты; detect changed
- [x] Строка в `example-tests/README.md`

**2.2 Декоратор шагов `module-flow`** (идея: cross-cutting без правки шагов)

- [x] `FlowStepDecorator`, `LoggingFlowStepDecorator`
- [x] `FlowRunnerFactory.create(steps, decorators)`; default — logging
- [x] Тест custom decorator в `FlowRunnerTest`
- [x] README module-flow: decorator API
- [x] Канонический кейс: `FlowRunnerTest.roleBasedApprovalPath` (ветвление по
  `role` в `FlowContext`)

**2.3 Маски-пресеты `module-data`** (идея: Object Mother «вкусы»)

- [x] `Generators.guid() / phone() / numeric() / alphanumeric()`
- [x] Покрытие в `DataHelpersTest`
- [x] README: Generators + ссылка на domain Object Mothers в адаптированном
  проекте

---

## Этап 3 — module-contract v2 (день) ✅

**3.1 JSON Schema под API `MessageContract`**

- [x] Заменить начинку `JsonContractValidator` на
  `com.networknt:json-schema-validator`
- [x] Сохранить API `MessageContract` / `ContractViolation` / `assertValid`
- [x] Строгий пресет `ObjectMapper` (дубликаты ключей, без коэрсий)
- [x] Приоритизация reason-кодов (required > type > …, контроль кардинальности)
- [x] Сырые JSON-схемы из ресурсов рядом с DSL
- [x] `JsonContractValidatorTest` и `KafkaProbeTest` — assert'ы без изменений

Граница: не consumer-driven contracts — см. `module-contract/README.md`.

---

## Этап 4 — module-data: пул и фикстуры (2–3 дня) ✅

**4.1 `@Prepared` + SPI**

- [x] JUnit 5 `ParameterResolver` `@Prepared`
- [x] SPI `PreparedDataProvider<T>`
- [x] In-memory пул + `PoolEventListener` (выдан / возвращён / исчерпан)
- [x] Заглушка провайдера для адаптации
- [x] Шаг в `AGENTS.md` adaptation playbook
- [x] Example test

Реинкарнация «пула подготовленных данных» как **класса в шаблоне**, не
отдельного сервиса.

---

## Этап 5 — документация и публикация (полдня) ✅ (push отложен)

**5.1 Roadmap в README / AGENTS** (будущие модули)

- [x] Gherkin-фрагменты (для Cucumber-компаний)
- [x] Multi-datasource routing в `module-db`
- [x] Обобщение kafka → messaging (RabbitMQ и т.д.)
- [x] Allure-интеграция (attachments: SQL, flow path, prewarm, resources)
- [x] Ссылка на `docs/ROADMAP.md` и gap analysis в README

**5.2 Плейбук AGENTS.md — client/DTO артефакты**

- [x] Правило: продукт публикует client/DTO → тест-модуль зависит от них, не
  дублирует (третий слой защиты от дрейфа рядом с `SchemaValidator` и
  `module-contract`)

**5.3 GitHub**

- [x] GitHub Actions workflow (`build.yml`)
- [ ] Репозиторий создан, push, зелёная страница Actions (если ещё не сделано
  локально)

---

## Production v1 gaps (из gap analysis) — P0 ✅, P1 ⬜

Критично перед параллельным staging — не дублирует этапы 1–5, дополняет:

| P0 | Описание |
|----|----------|
| [x] | `ScenarioContextExtension` — auto `clear()` после теста |
| [x] | Test scope helper → `MockScope` correlation |
| [x] | `application-staging.example.yml` |
| [x] | `docs/adaptation-checklist.md` |
| [x] | Dockerfile — все `module-*/build.gradle` в warmup |

| P1 | Описание |
|----|----------|
| [x] | Optional Testcontainers example (`@Tag("containers")`, `containersTest`) |
| [x] | Allure: AllureFlowStepDecorator + AllureResourceAttachments (optional deps) |
| [x] | `SchemaValidator` — `@Embedded` |
| [x] | Parallel tests guide (docs/parallel-tests.md) |
| [x] | RestAssured + scope HTTP example (ScopedRequestTemplateTest) |

Детали: [production-v1-gap-analysis.md](production-v1-gap-analysis.md).

---

## Hygiene

Clean-room: код чужих проектов не переносится — только идеи. Перед коммитом:

```bash
git grep -iE 'previous-employer domain terms' || true
```

Доменные термины работодателей — только в этом roadmap как метки источника идеи,
не в `src/`.

---

## Definition of Done — template v1.0.0

- [x] Этапы 1–2 закоммичены и зелёные
- [x] Этап 3 или явный defer с причиной в README contract module
- [x] P0 production gaps закрыты
- [x] `example-tests/README` = все тест-классы
- [x] `./gradlew build` без внешних сервисов (embedded Kafka в default build —
  ок, брокер в JVM)

---

*Living doc — обновлять статусы при merge.*
