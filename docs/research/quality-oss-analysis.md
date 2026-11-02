# The quality module against the market: summary and plan

Sources: a Gemini Deep Research report, "Overview of software quality
assessment systems" (59 pages, November 2026; the request is
[quality-oss-analysis-prompt.md](quality-oss-analysis-prompt.md)), and a note on
Staff-Quality-level metrics (defect escape rate, CFR, mutation score). Figures
and links come from the report and were not checked separately.

## 1. What the market confirms: we already have it

| Market idea | Where in the market | In `module-quality` |
|---|---|---|
| History without a server, in CI files | Allure 3 `history.jsonl`, Qodana SARIF baseline | `forge.quality.history.dir`, `run-<id>.json`, window and rotation |
| Gate on what is new only | SonarQube New Code, Codecov patch | baseline: new / accepted / known / resolved |
| Risk acceptance with an expiry | DefectDojo risk acceptance | `accepted` with `until <date>`; an unused entry is reported |
| An empty run is not green | Allure 3 `minTestsCount`, Backstage Tech Insights | NOT MEASURED ≠ PASS, `required`, fail-closed |
| A hard veto | Keptn `key_sli` | `fail-on` |
| Robust statistics instead of thresholds | Kayenta, Lighthouse CI median | median + k×MAD, the machine's pace divided out, `min-runs` |
| Owners and components | Codecov Components, Trunk + CODEOWNERS | `components` with globs and an owner |
| Per-dimension thresholds and budgets | Codecov `target`, Allure `maxFailures` | `gate.<dim>.min-score`, `max-new-issues` |
| Output without a UI | Allure `quality-gate.json`, jacoco-report PR comment | `quality.json` + schema, `quality-summary.md`, `quality.prom` |
| Flaky on one revision | Develocity, DeFlaker (the "code did not change" idea) | `stability` by `revision` |

Conclusion: the architecture (read a run's artifacts, formulas, fail-closed,
files instead of a database) matches where the market is going (trend 4 of
the report). The foundation needs no change — the gaps below do.

## 2. Gaps worth closing

Ordered by value for size. Every item fits the module's invariants (`core`
only, reading files, a formula over facts, no database).

| # | Idea | Source | What changes here | Size |
|---|---|---|---|---|
| 1 | **Dry run for gate rules** | Argo Rollouts `dryRun` | `gate.<dim>.dry-run: true` — "would fail" in the report, the build does not fail. A safe way to turn on a new `fail-on` | S |
| 2 | **A "how to fix" hint** on each issue | Tech Insights `solution`, Sonar MCP | a `hint` on the dimension / issue in `quality.json` and the summary; useful to people and agents alike | S |
| 3 | **A minimum of executed tests** | Allure 3 `minTestsCount` | `forge.quality.min-tests`: `functional` FAILs when fewer ran (a typo in a filter is not a green run) | S |
| 4 | **Assertion vs broken, failure categories** | Allure categories, ReportPortal pattern analysis | JUnit `<failure>` and `<error>` are already distinct in the XML; split `functional`'s facts into product / test-or-environment, plus ordered `categories` rules (exception type, regex on the first line) | S |
| 5 | **Quarantine with a failure fingerprint** | Trunk failure fingerprinting, DefectDojo | extend `accepted`: `fingerprint` = hash of the exception type and normalised top frames; the same — accepted, another — new. The test keeps running. Implements the "quarantined" of spec §3.2 | M |
| 6 | **Compare with green runs only** | Keptn `include_result_with_score: pass` | operation p95 in `efficiency` comes from every history entry; take entries without a FAIL only | S |
| 7 | **Flaky rehabilitation and a switch counter** | Allure `stabilizationPeriod`, ReportPortal "switches N of M" | in `stability`'s facts: "switched N of M"; a test that passed K times in a row after being flaky is marked stabilized (information, not an issue) | S |
| 8 | **Mutation score (PIT / Stryker)** | PIT `mutations.xml`, Stryker `thresholds.break` | a new `mutation` dimension: `100 × killed / (killed + survived)` from PIT's report; FAIL below `min-mutation-score`; the project runs PIT, we only read | M |
| 9 | **Diff coverage** | diff-cover, diff-coverage-gradle, Codecov patch | a new `diff-coverage` dimension: JaCoCo XML ∩ `git diff` against the merge base; a small diff is NOT MEASURED with the reason, not a silent PASS (Sonar antipattern 7) | M |
| 10 | **Components from CODEOWNERS** | Trunk, Codecov | `components.from-codeowners: true` — less configuration by hand | S |
| 11 | **`quality.prom` cardinality** | antipattern 8 of the report | `testforge_quality_run_info` carries `revision` / `job` as labels — unbounded series growth for a push gateway. Document it or make it an option | S |

### 2.1 What of "Staff Quality" belongs here

The note's metrics fall into two groups:

- **The strength of the checks themselves** — mutation score / test strength,
  diff coverage, a flaky rate under 1–2 %, quarantine with an owner and an
  expiry. This is our layer: items 5, 7, 8, 9 above. The `max-flaky-rate: 0.02`
  threshold already matches the benchmark.
- **Production outcomes** — defect escape rate, DRE, change failure rate,
  crash-free sessions, SLOs under real traffic. A test run does not see them.
  An option for later: an optional `release-health.json` input (facts a CI or
  incident system leaves next to it) and a dimension that only reads it. Out
  of scope until then, like SAST/SCA in spec §5.

## 3. What we deliberately leave out

| Idea | Why not |
|---|---|
| Keptn's weighted total score (warn = 50 %, weights) deciding the gate | The spec: "No single magic number decides a release". Overall is the worst status. Weights are acceptable for a trend line at most |
| Mann-Whitney U for durations | Needs ~50 points per test; with a window of 10 runs the status is almost always NODATA. MAD + `min-runs` gives the same without false rigour |
| ML triage (ReportPortal auto-analysis), predicting flakiness from code | Opaque; the report itself cites antipattern 6 (replicating a wrong first labelling) and 2026 papers on poor transfer |
| Rerunning new tests N times in a PR (Datadog new flaky gate) | `module-quality` never reruns a test. Possible as a separate Gradle task in `example-tests`, its result read as ordinary JUnit XML |
| DeFlaker (per-test coverage ∩ diff) | Needs per-test JaCoCo instrumentation — L, a module of its own. Revisit after `diff-coverage` |
| An own CEL / Rego rule engine | YAML thresholds suffice today; an expression engine is a new dependency and a DSL (against "keep stable") |
| SARIF for quality issues | SARIF is about places in code; `security` already writes it. Little value for test issues |
| OpenTelemetry CI/CD spans | The specification is a release candidate; revisit once stable. `quality.prom` already covers metrics |

## 4. Answers to the spec's open questions (§7)

1. **Override formula weights?** No. Where the market makes weights
   configurable (Keptn, Scorecard) it still keeps them fixed and documented;
   what is configurable is **thresholds** (`min-score`, `max-new-issues`, and
   later `min-mutation-score`, `min-diff-coverage`) and **mode** (`fail-on`,
   `dry-run`). That keeps a formula reproducible.
4. **Severity of security checks — fixed or overridable?** Fixed in the
   module; overriding goes through `accepted` with an expiry (DefectDojo's
   model), not through changing a severity.

## 5. Proposed steps

In the format of spec §6; one PR per step.

| Step | Deliverable | Acceptance |
|---|---|---|
| A5 | Gate: per-dimension `dry-run`, `min-tests`, a `hint` on dimensions (items 1–3) — **done** | a dry-run rule prints "would fail" and does not fail the gate; 0 tests with `min-tests: 1` — FAIL |
| ~~A6~~ | Own `categories` — **dropped**: these are Allure Report's categories, and Allure is already an optional part of TestForge. The assertion / error split moved to A7 | — |
| A7 | Assertion vs error failures in `functional`'s facts + quarantine by fingerprint (items 4–5) — **done** | the same stack trace — accepted; another exception type in the same test — new, the gate fails; an expired `until` — new |
| A8 | History: green runs only for p95, "switches N of M", stabilization (items 6–7) — **done** | a red run in the history does not shift the median; a test after 5 green runs in a row is marked stabilized |
| A9 | `mutation` dimension from PIT's `mutations.xml` (item 8) — **done** | an example with a surviving mutant; the formula checks out by hand; no report — NOT MEASURED |
| A10 | `diff-coverage` dimension — **an adapter**: reads the report diff-cover / diff-coverage-gradle already computed (JaCoCo ∩ git diff) rather than computing it again (item 9) — **done**, diff-cover's layout checked against its `report_generator.py` | an uncovered changed line is shown with its file and number; a small diff is NOT MEASURED with the reason |
| A11 | CODEOWNERS for components, `quality.prom` cardinality (items 10–11) — **done** | components come from CODEOWNERS; `run_info` without `revision` by default |

The selection rule is the spec's "Compose, do not reimplement": compute only
what no existing report already has; read the rest as an input.

A5–A8 improve what exists, with no new inputs. A9–A10 are new "strength of
the tests" dimensions — what was missing for a Staff-Quality-level argument.
The field test on a real application that followed is
[quality-dogfood-petclinic.md](quality-dogfood-petclinic.md).
