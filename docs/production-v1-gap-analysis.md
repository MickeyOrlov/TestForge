# TestForge — gap analysis для production-ready v1

Документ для адаптеров шаблона: что уже закрыто, что критично добавить перед
боевым использованием, и как TestForge стыкуется с Testcontainers и Pact.

**Связка:** этапный план из архива и статусы — в [ROADMAP.md](ROADMAP.md).
Этот файл — дополнение (production staging P0/P1), не замена этапов 1–5.

**Контекст:** шаблон v0.1.0, offline example suite, модульная архитектура.
Целевой v1 — «команда клонировала, адаптировала за 1–2 спринта и гоняет
параллельные integration-тесты на staging без постоянного ручного тюнинга».

---

## 1. Снимок текущей готовности

| Область | Статус | Комментарий |
|--------|--------|-------------|
| Модульная архитектура | ✅ | Модули удаляемы, auto-config, `forge.*` |
| Offline reference tests | ✅ | H2 + embedded WireMock + `@EmbeddedKafka` в collector test |
| CI (GitHub + GitLab) | ✅ | `build`, junit reports, manual env jobs, runner image |
| Scoped mock pattern | ✅ | `MockScope` + пример изоляции |
| Async waits (no sleep) | ✅ | `Waiter`, `DbWaiter`, Kafka probe |
| Schema drift (test entities) | ✅ | `@Column`/`@JoinColumn`/`@Embedded`+`@AttributeOverride`; inheritance/naming — нет |
| Lifecycle / test hygiene | ✅ | `ScenarioContextExtension` + пример |
| Testcontainers | ✅ | TC 2.x, `PostgresSchemaValidationIT`, `containersTest` + CI jobs |
| Reporting (Allure) | ✅ | optional: `AllureFlowStepDecorator`, `AllureResourceAttachments` |
| Adaptation templates | ✅ | `application-staging.example.yml`, `adaptation-checklist.md` |
| Docker runner warmup | ✅ | все модули в COPY + docker-build CI job |
| Contract depth | ✅ | DSL заморожен + `SchemaContract` (networknt) |
| Data pools | ✅ | `@Prepared` + `PreparedDataPool` + listeners |
| Distributed tracing / correlation | ✅ | `scope()` → `TEST_SCOPE` в ScenarioContext; OTel-линковка — P2 |

**Итог:** ядро идеи реализовано; для production v1 не хватает **операционной
обвязки** (lifecycle, reporting, env templates, Testcontainers option) и
**документации адаптации** на реальный staging.

---

## 2. Gap backlog (приоритеты)

### P0 — без этого боевой запуск рискован

| # | Gap | Проблема | Рекомендация |
|---|-----|----------|--------------|
| 1 | **JUnit extension для очистки контекста** | `ScenarioContext` и `RunUniqueValues` могут протекать между тестами на одном worker thread | ✅ `ScenarioContextExtension`. Reset `RunUniqueValues` per class — **осознанный отказ**: реестр обязан жить весь прогон, иначе теряется гарантия уникальности между классами |
| 2 | **Шаблон correlation / test scope** | Без `forge.mock.scope-json-path` параллельные тесты на shared WireMock ломаются | Модуль или core helper: генерация `testScopeId`, положить в `ScenarioContext`, пример прокидывания в API header/body; чеклист в adaptation doc |
| 3 | **Пример `application-<env>.yml`** | Новая команда не знает, как выглядит staging profile | `example-tests/src/test/resources/application-staging.example.yml` (заглушки, env vars) + ссылка в README |
| 4 | **Исправить Dockerfile warmup** | Сейчас копируются только часть `module-*/build.gradle` — при добавлении модулей image build может сломаться | Копировать все module build.gradle или один `COPY module-*/build.gradle` |
| 5 | **Adaptation checklist (human)** | AGENTS.md ориентирован на AI; людям нужен короткий чеклист | `docs/adaptation-checklist.md`: rename, scope field, entities, delete modules, CI secrets |

### P1 — сильно повышает ценность v1

| # | Gap | Проблема | Рекомендация |
|---|-----|----------|--------------|
| 6 | **Testcontainers (optional module или profile)** | Многие teams поднимают Kafka/Postgres локально в CI | `module-testcontainers` или profile в example-tests: Postgres + Kafka containers; не mandatory — offline suite остаётся default |
| 7 | **Allure attachments** | SQL log, flow path, mock scope, resource stats теряются в CI | `module-reporting`: optional Allure listener; attachments для `forge.sql`, `FlowException` path, `ResourceUsageStats` |
| 8 | **Расширить SchemaValidator** | `@Embedded`, inheritance — частый кейс в JPA | Поэтапно: `@Embedded` + `@AttributeOverride`; явно документировать unsupported |
| 9 | **StateSnapshot в example-tests README** | `StateDiff`/`StateSnapshot` в core без строки в living doc table | Добавить `StateDiffTest` в example-tests README |
| 10 | **Parallel execution guide** | JUnit parallel + Spring context + shared WireMock — нюансы | Док: `@Execution(CONCURRENT)`, fork count, когда serial tag |
| 11 | **RestAssured / HTTP baseline** | Нет единого примера «вызвать SUT REST» | Один example test: RestAssured + scope header + scoped mock downstream |

### P2 — после v1 / по запросу адаптеров

| # | Gap | Рекомендация |
|---|-----|--------------|
| 12 | Prepared data pools | Roadmap item — отдельный модуль |
| 13 | JSON Schema behind `MessageContract` | Swap validator per module-contract README |
| 14 | **ScopedValue transition** | `ThreadLocal` не оптимален для виртуальных потоков | Заменить на `ScopedValue` в `ScenarioContext` (Java 26) |
| 15 | **AI-native positioning** | Проект не кричит о своей главной фиче в README | Бейдж "AI-native", акцент в описании на "Agent-friendly" архитектуру |
| 16 | WireMock Cloud / remote admin API | Док для teams без self-hosted WireMock |
| 15 | OpenTelemetry trace id → scope | Автолинковка scope с trace id для async pipelines |
| 16 | Gradle version catalog publish | Если захотят library mode — сейчас сознательно template-only |
| 17 | Kotlin DSL / multi-module test layout | Для teams с несколькими service DB modules |

---

## 3. TestForge vs Testcontainers + Pact

Не «замена», а **разные слои**. Типичная production-схема — композиция.

```
┌─────────────────────────────────────────────────────────────┐
│  TestForge (template framework)                              │
│  waits, scoped mocks, DB gray-box, flow setup, drift checks │
├─────────────────────────────────────────────────────────────┤
│  Testcontainers (infra)     │  Pact / SCC (cross-team API)  │
│  broker, DB, dependencies   │  provider/consumer contracts  │
└─────────────────────────────────────────────────────────────┘
```

### Testcontainers

| Вопрос | Testcontainers | TestForge сегодня | Как совместить |
|--------|----------------|-------------------|----------------|
| Поднять Kafka/Postgres в CI | ✅ Primary use case | ❌ Нет | Profile `local-containers`: `@ServiceConnection` или manual `@Container` |
| Тесты против staging | Не цель | ✅ Primary use case | Staging profile без containers |
| Скорость CI | Медленнее старт | Быстрый offline suite | Default CI = offline; nightly = containers |
| Kafka message assert | Нужен consumer | `KafkaProbe` + buffer | Container broker + `forge.kafka.enabled=true` |
| DB schema assert | Не делает | `SchemaValidator` на JPA entities | Container Postgres + entities против реальной схемы |

**Вердикт:** TestForge **не конкурирует** с Testcontainers. Для v1 добавить
**optional** пример/модуль — снизит friction при адаптации. Offline example
suite оставить каноническим для `./gradlew build`.

### Pact / Spring Cloud Contract

| Вопрос | Pact / SCC | TestForge `module-contract` |
|--------|------------|------------------------------|
| Cross-team contract | ✅ | ❌ Явно не цель |
| Provider verification | ✅ | ❌ |
| QA-side shape drift | Частично (schema) | ✅ Лёгкий drift на staging |
| Kafka event shape | Pact plugins / SCC | `MessageContract` + `KafkaProbe` |
| Сложные правила (regex, enum) | ✅ | ❌ → JSON Schema swap |

**Вердикт:** держать **module-contract для cheap QA drift** на реальных
сообщениях с staging. Pact/SCC — для **границ между командами**. В adaptation
checklist: таблица «когда Pact, когда TestForge contract».

### Scoped WireMock vs Hoverfly / Mountebank

| | Shared WireMock + scope | Per-test mock instance |
|--|-------------------------|-------------------------|
| Ops | Один сервер на env | Много инстансов / sidecars |
| Parallel | Нужен scope field в SUT | Изоляция по умолчанию |
| TestForge | ✅ Core value prop | Не покрыто |

TestForge заточен под **enterprise staging с одним WireMock** — это сознательная
ниша, не недостаток.

---

## 4. Рекомендуемый scope v1.0.0

Минимальный релиз шаблона «production-ready» без раздувания:

### В коде

1. `ScenarioContextExtension` (+ регистрация через auto-config или `@ExtendWith`)
2. `TestScope` helper (generate id + `ContextKey` + optional HTTP header name property)
3. Dockerfile fix (все module build.gradle)
4. `application-staging.example.yml`
5. (Опционально) `TestcontainersExampleTest` в example-tests, `@Tag("containers")`, не в default `build`

### В документации

1. `docs/adaptation-checklist.md`
2. `docs/parallel-tests.md`
3. Обновить example-tests README (StateDiff, Generators)
4. Секция в README: «TestForge + Testcontainers + Pact» (короткая, ссылка сюда)

### В CI

1. Job `build` — без containers (как сейчас)
2. Job `containers` — manual или nightly, `-Dgroups=containers`
3. Проверить runner image после Dockerfile fix

### Версионирование

- **v0.1.0** — текущее состояние
- **v1.0.0** — после P0 + доки + (опционально) P1 Allure/Testcontainers example

---

## 5. Что не делать в v1

- Превращать шаблон в published Maven library (ломает positioning)
- Растить homemade contract DSL — JSON Schema facade при необходимости
- Mandatory Testcontainers в default `build` (сломает offline-first)
- Full UI framework — prewarm достаточен для v1
- Data pools — отдельный большой модуль, не блокер v1

---

## 6. Матрица «проблема → инструмент»

| Проблема | TestForge | Testcontainers | Pact/SCC |
|----------|-----------|----------------|----------|
| Flaky sleep | `Waiter` | — | — |
| Parallel mock clashes | `MockScope` | — | — |
| Async DB row | `DbWaiter` | — | — |
| Entity vs DB drift | `SchemaValidator` | Postgres container | — |
| Kafka assert in test | `KafkaProbe` | Kafka container | — |
| Event shape drift (QA) | `module-contract` | — | частично |
| API contract between teams | — | — | ✅ |
| Local CI without staging | offline examples | ✅ | broker tests |
| Cold UI env | `PrewarmRunner` | — | — |
| CI OOM/slow diagnostics | `ResourceUsageMonitor` | — | — |
| Long setup paths | `FlowRunner` | — | — |
| Duplicate rows / side effects | `StateSnapshot` | — | — |

---

## 7. Оценка effort (ориентировочно)

| Пакет | Effort | Impact |
|-------|--------|--------|
| P0 (extension, scope helper, dockerfile, env example, checklist) | 2–4 дня | Критичный |
| P1 Testcontainers example | 1–2 дня | Высокий |
| P1 Allure attachments | 2–3 дня | Высокий для enterprise CI |
| P1 SchemaValidator `@Embedded` | 2–4 дня | Средний, domain-specific |
| P2 data pools | 1–2 спринта | По запросу |

---

## 8. Definition of Done для v1.0.0

- [x] `./gradlew build` green без внешних сервисов
- [x] Новый адаптер проходит `docs/adaptation-checklist.md` без «скрытых» шагов
- [x] `ScenarioContext` не протекает между тестами в parallel run
- [x] Есть рабочий пример test scope → mock isolation → REST call
- [x] Docker runner image build проходит с полным набором модулей
- [x] Документирована связка с Testcontainers и Pact (этот файл + README anchor)
- [x] example-tests README соответствует все тест-классам

---

*Сгенерировано как living doc — обновлять при закрытии gaps.*
