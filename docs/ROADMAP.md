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
| 2 | StateDiff, Flow decorator, Generators | 🟡 код + README + role flow — готово локально, ждёт коммит |
| 3 | module-contract v2 (JSON Schema) | ⬜ |
| 4 | `@Prepared` data pools | ⬜ |
| 5 | Документация и публикация | 🟡 частично (CI есть, AGENTS/README — ⬜) |
| 6 | Open Source Readiness & Modernization | ⬜ |
| 7 | UI & Mobile Expansion | ⬜ |
| — | Production v1 gaps (P0) | ⬜ см. gap analysis |

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
- [ ] Implement `ScenarioContextExtension` for auto-cleanup (move from P0 gaps)
- [ ] Integration: JSON Schema behind `MessageContract` (move from Stage 3)

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

## Этап 2 — идеи из архива (день) 🟡

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

## Этап 3 — module-contract v2 (день) ⬜

**3.1 JSON Schema под API `MessageContract`**

- [ ] Заменить начинку `JsonContractValidator` на
  `com.networknt:json-schema-validator`
- [ ] Сохранить API `MessageContract` / `ContractViolation` / `assertValid`
- [ ] Строгий пресет `ObjectMapper` (дубликаты ключей, без коэрсий)
- [ ] Приоритизация reason-кодов (required > type > …, контроль кардинальности)
- [ ] Сырые JSON-схемы из ресурсов рядом с DSL
- [ ] `JsonContractValidatorTest` и `KafkaProbeTest` — assert'ы без изменений

Граница: не consumer-driven contracts — см. `module-contract/README.md`.

---

## Этап 4 — module-data: пул и фикстуры (2–3 дня) ⬜

**4.1 `@Prepared` + SPI**

- [ ] JUnit 5 `ParameterResolver` `@Prepared`
- [ ] SPI `PreparedDataProvider<T>`
- [ ] In-memory пул + `PoolEventListener` (выдан / возвращён / исчерпан)
- [ ] Заглушка провайдера для адаптации
- [ ] Шаг в `AGENTS.md` adaptation playbook
- [ ] Example test

Реинкарнация «пула подготовленных данных» как **класса в шаблоне**, не
отдельного сервиса.

---

## Этап 5 — документация и публикация (полдня) 🟡

**5.1 Roadmap в README / AGENTS** (будущие модули)

- [ ] Gherkin-фрагменты (для Cucumber-компаний)
- [ ] Multi-datasource routing в `module-db`
- [ ] Обобщение kafka → messaging (RabbitMQ и т.д.)
- [ ] Allure-интеграция (attachments: SQL, flow path, prewarm, resources)
- [x] Ссылка на `docs/ROADMAP.md` и gap analysis в README

**5.2 Плейбук AGENTS.md — client/DTO артефакты**

- [ ] Правило: продукт публикует client/DTO → тест-модуль зависит от них, не
  дублирует (третий слой защиты от дрейфа рядом с `SchemaValidator` и
  `module-contract`)

**5.3 GitHub**

- [x] GitHub Actions workflow (`build.yml`)
- [ ] Репозиторий создан, push, зелёная страница Actions (если ещё не сделано
  локально)

---

## Production v1 gaps (из gap analysis) ⬜

Критично перед параллельным staging — не дублирует этапы 1–5, дополняет:

| P0 | Описание |
|----|----------|
| [ ] | `ScenarioContextExtension` — auto `clear()` после теста |
| [ ] | Test scope helper → `MockScope` correlation |
| [ ] | `application-staging.example.yml` |
| [ ] | `docs/adaptation-checklist.md` |
| [x] | Dockerfile — все `module-*/build.gradle` в warmup |

| P1 | Описание |
|----|----------|
| [ ] | Optional Testcontainers example (`@Tag`) |
| [ ] | Allure attachments |
| [ ] | `SchemaValidator` — `@Embedded` |
| [ ] | Parallel tests guide |
| [ ] | RestAssured + scope HTTP example |

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

- [ ] Этапы 1–2 закоммичены и зелёные
- [ ] Этап 3 или явный defer с причиной в README contract module
- [ ] P0 production gaps закрыты
- [ ] `example-tests/README` = все тест-классы
- [ ] `./gradlew build` без внешних сервисов (embedded Kafka в default build —
  ок, брокер в JVM)

---

*Living doc — обновлять статусы при merge.*
ика идеи,
не в `src/`.

---

## Definition of Done — template v1.0.0

- [ ] Этапы 1–2 закоммичены и зелёные
- [ ] Этап 3 или явный defer с причиной в README contract module
- [ ] P0 production gaps закрыты
- [ ] `example-tests/README` = все тест-классы
- [ ] `./gradlew build` без внешних сервисов (embedded Kafka в default build —
  ок, брокер в JVM)

---

*Living doc — обновлять статусы при merge.*
