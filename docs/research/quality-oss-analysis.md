# Модуль качества против рынка: выжимка и план

Источники: отчёт Gemini Deep Research «Обзор систем оценки качества ПО»
(59 стр., ноябрь 2026; запрос — [quality-oss-analysis-prompt.md](quality-oss-analysis-prompt.md))
и заметка о метриках уровня Staff Quality (defect escape rate, CFR,
mutation score). Цифры и ссылки — из отчёта, отдельно не перепроверялись.

## 1. Что рынок подтверждает: у нас уже есть

| Идея рынка | Где на рынке | В `module-quality` |
|---|---|---|
| История без сервера в файлах CI | Allure 3 `history.jsonl`, Qodana SARIF baseline | `forge.quality.history.dir`, `run-<id>.json`, окно и ротация |
| Гейт только по новому | SonarQube New Code, Codecov patch | baseline: new / accepted / known / resolved |
| Принятие риска со сроком | DefectDojo risk acceptance | `accepted` с `until <date>`, неиспользованная запись видна |
| Пустой прогон не зелёный | Allure 3 `minTestsCount`, Backstage Tech Insights | NOT MEASURED ≠ PASS, `required`, fail-closed |
| Жёсткое вето | Keptn `key_sli` | `fail-on` |
| Устойчивая статистика вместо порогов | Kayenta, Lighthouse CI median | median + k×MAD, поправка на темп машины, `min-runs` |
| Владельцы и компоненты | Codecov Components, Trunk + CODEOWNERS | `components` с glob и owner |
| Пороги и бюджеты на измерение | Codecov `target`, Allure `maxFailures` | `gate.<dim>.min-score`, `max-new-issues` |
| Выход без UI | Allure `quality-gate.json`, jacoco-report PR comment | `quality.json` + schema, `quality-summary.md`, `quality.prom` |
| Flaky по одной ревизии | Develocity, DeFlaker (идея «код не менялся») | `stability` по `revision` |

Вывод: архитектура (читать артефакты прогона, формулы, fail-closed, файлы
вместо БД) совпадает с тем, куда движется рынок (тренд 4 отчёта). Менять
основу не нужно — нужно закрыть пробелы ниже.

## 2. Пробелы, которые стоит закрыть

Отсортировано по ценности к размеру. Все пункты совместимы с инвариантами
модуля (только `core`, чтение файлов, формула над фактами, без БД).

| # | Идея | Источник | Что меняется у нас | Размер |
|---|---|---|---|---|
| 1 | **Dry-run для правил гейта** | Argo Rollouts `dryRun` | `gate.<dim>.dry-run: true` — в отчёте «would fail», билд не падает. Безопасное включение новых `fail-on` | S |
| 2 | **Подсказка «как исправить»** у каждого issue | Tech Insights `solution`, Sonar MCP | поле `hint` у измерения/issue в `quality.json` и summary; полезно и людям, и агентам | S |
| 3 | **Минимум выполненных тестов** | Allure 3 `minTestsCount` | `forge.quality.min-tests`: `functional` FAIL, если выполнено меньше (опечатка в фильтре ≠ зелёный прогон) | S |
| 4 | **Assertion vs broken + категории падений** | Allure categories, ReportPortal pattern analysis | JUnit `<failure>` vs `<error>` уже различимы в XML; разделить в фактах `functional` на product / test-or-env, плюс упорядоченные правила `categories` (тип исключения, regex по первой строке) | S |
| 5 | **Карантин с отпечатком падения** | Trunk failure fingerprinting, DefectDojo | расширить `accepted`: `fingerprint` = хэш типа исключения + нормализованных верхних фреймов; совпал — accepted, другой — new. Тест продолжает выполняться. Реализует «quarantined» из спеки §3.2 | M |
| 6 | **Сравнение только с зелёными прогонами** | Keptn `include_result_with_score: pass` | p95 операций в `efficiency` сейчас берётся из всех записей истории; брать только записи без FAIL | S |
| 7 | **Авто-реабилитация flaky и счётчик переключений** | Allure `stabilizationPeriod`, ReportPortal «switches N of M» | в фактах `stability`: «переключений N из M»; тест, прошедший K раз подряд после flaky, помечается стабилизировавшимся (информация, не issue) | S |
| 8 | **Mutation score (PIT/Stryker)** | PIT `mutations.xml`, Stryker `thresholds.break` | новое измерение `mutation`: `100 × killed / (killed + survived)` по отчёту PIT; FAIL ниже `min-mutation-score`; PIT запускает проект сам, мы только читаем | M |
| 9 | **Diff coverage** | diff-cover, diff-coverage-gradle, Codecov patch | новое измерение `diff-coverage`: JaCoCo XML ∩ `git diff` к merge-base; маленький diff — NOT MEASURED с причиной, а не тихий PASS (антипаттерн 7 Sonar) | M |
| 10 | **Компоненты из CODEOWNERS** | Trunk, Codecov | `components.from-codeowners: true` — меньше ручной конфигурации | S |
| 11 | **Кардинальность `quality.prom`** | антипаттерн 8 отчёта | `testforge_quality_run_info` несёт `revision` / `job` как метки — для push gateway это бесконечный рост рядов. Документировать или вынести в опцию | S |

### 2.1 Что из «Staff Quality» сюда относится

Метрики из заметки делятся на две группы:

- **Сила самих проверок** — mutation score / test strength, diff coverage,
  flaky rate < 1–2 %, карантин с владельцем и сроком. Это наш слой: пункты
  5, 7, 8, 9 выше. Порог `max-flaky-rate: 0.02` уже совпадает с ориентиром.
- **Продакшен-исходы** — defect escape rate, DRE, change failure rate,
  crash-free sessions, SLO под реальным трафиком. Тестовый прогон их не
  видит. Вариант на будущее: опциональный вход `release-health.json`
  (факты, которые CI/инцидент-система кладёт рядом) и измерение, которое
  только читает их. До этого — вне скоупа, как SAST/SCA в спеке §5.

## 3. Что сознательно не берём

| Идея | Почему нет |
|---|---|
| Взвешенный общий балл Keptn (warn = 50 %, веса) решает гейт | Спека: «No single magic number decides a release». Overall — худший статус. Веса допустимы разве что для линии тренда |
| Mann-Whitney U для длительностей | Нужно ~50 точек на тест; при окне 10 прогонов статус почти всегда NODATA. MAD + `min-runs` даёт то же без ложной строгости |
| ML-триаж (ReportPortal auto-analysis), предсказание flaky по коду | Непрозрачно; отчёт сам приводит антипаттерн 6 (тиражирование ошибочной разметки) и работы 2026 г. о непереносимости |
| Перезапуск новых тестов N раз в PR (Datadog new flaky gate) | `module-quality` никогда не перезапускает тесты. Можно как отдельную Gradle-задачу в `example-tests`, результат читается как обычный JUnit XML |
| DeFlaker (per-test coverage ∩ diff) | Требует per-test инструментации JaCoCo — L, отдельный модуль. Вернуться после `diff-coverage` |
| Свой CEL/Rego-движок правил | Сегодня хватает YAML-порогов; движок выражений — новая зависимость и DSL (против «keep stable») |
| SARIF для quality-issues | SARIF про места в коде; у `security` он уже есть. Для тестовых issues ценности мало |
| OpenTelemetry-спаны CI/CD | Спецификация в RC; вернуться после стабилизации. `quality.prom` уже закрывает метрики |

## 4. Ответы на открытые вопросы спеки (§7)

1. **Переопределять веса формул?** Нет. Рынок, где веса настраиваемы
   (Keptn, Scorecard), всё равно держит их фиксированными и
   документированными; настраиваются **пороги** (`min-score`,
   `max-new-issues`, будущие `min-mutation-score`, `min-diff-coverage`) и
   **режим** (`fail-on`, `dry-run`). Так формула остаётся воспроизводимой.
4. **Severity проверок безопасности — фиксировано или переопределяемо?**
   Фиксировано в модуле, переопределение — через `accepted` со сроком
   (модель DefectDojo), а не через смену severity.

## 5. Предлагаемые шаги

Формат как в спеке §6; по одному PR на шаг.

| Step | Deliverable | Acceptance |
|---|---|---|
| A5 | Гейт: `dry-run` по измерению, `min-tests`, `hint` у измерений (пункты 1–3) — **сделано** | правило в dry-run печатает «would fail» и не роняет гейт; 0 тестов при `min-tests: 1` — FAIL |
| A6 | Триаж падений: failure vs error, `categories` (пункт 4) | пример с assertion-падением и `IOException` — разные категории в `quality.md` |
| A7 | Карантин с отпечатком (пункт 5) | тот же стектрейс — accepted; другой тип исключения в том же тесте — new, гейт падает; истёкший `until` — new |
| A8 | История: только зелёные прогоны для p95, «switches N of M», стабилизация (пункты 6–7) | красный прогон в истории не сдвигает медиану; тест после 5 зелёных подряд помечен стабилизировавшимся |
| A9 | Измерение `mutation` из PIT `mutations.xml` (пункт 8) | пример с выжившим мутантом; формула считается руками; без отчёта — NOT MEASURED |
| A10 | Измерение `diff-coverage` из JaCoCo + git diff (пункт 9) | непокрытая изменённая строка видна с файлом и номером; маленький diff — NOT MEASURED с причиной |
| A11 | CODEOWNERS для компонентов, кардинальность `quality.prom` (пункты 10–11) | компоненты собираются из CODEOWNERS; `run_info` без `revision` по умолчанию |

A5–A8 — улучшения существующего, без новых входов. A9–A10 — новые
измерения «силы тестов», то, чего сейчас не хватает для аргумента уровня
Staff Quality.
