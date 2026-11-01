# module-quality

Judges a finished run of the product's tests: reads the JUnit XML results
and the run index `module-reporting` wrote, scores the product along named
dimensions, writes `quality.md` / `quality.json`, and gates the build.
It never re-runs a test. Spec: [docs/quality-security-spec.md](../docs/quality-security-spec.md).

## Dimensions

Each dimension has a status (PASS / WARN / FAIL / NOT MEASURED), a score
0–100 by a formula printed next to it, and the facts it was computed from.

| Dimension | Formula | FAIL when | Reads |
|---|---|---|---|
| `functional` | `100 × passed / (passed + failed)` | any test failed | JUnit XML |
| `stability` | `100 × (1 − flaky / executed)` | flaky rate above `max-flaky-rate` (WARN below it) | JUnit XML, history |
| `contracts` | `100 − 10 × mismatches` | any mismatch | `module-api-explorer` contract guard, `module-contract-monitor` |
| `data` | `100 − 50 × breaking − 20 × risky − 5 × unknown` | a breaking schema change (WARN on risky/unknown) | `module-db-contract` |
| `robustness` | `100 − 10 × finding groups` | a fuzz finding group nobody accepted | `module-api-fuzz` `findings.json` |
| `authorization` | `100 − 20 × violations` | a role got in, or was shut out, against the access matrix | `module-api-explorer` access matrix |
| `efficiency` | `100 × (compared − slower) / compared` | never (WARN): with history, a test or an API operation's p95 above its median + `mad-factor` × MAD; without, a test ≥ `slowdown-factor`× **and** ≥ `min-millis` slower than in the baseline | JUnit durations, `http-summary.json`, history or baseline |
| `security` | `100 − 40 × critical − 20 × high − 5 × medium − 1 × low` (new findings) | a new critical or high finding (WARN on medium/low) | `module-security` `security-findings.json` |
| `reliability` | `100 × requests without 5xx / requests` | an operation answered 5xx or failed in transport — even where the test expected it | `module-http` `http-summary.json` |
| `db-queries` | `100 − 10 × flagged statement shapes` | a statement failed (swallowed or not) or wrote every row of a table; WARN on one over `forge.db.queries.slow-millis` | `module-db` `db-queries.json` |
| `api-coverage` | `100 × called / documented operations` | below `min-api-coverage` (WARN while any operation is uncalled) | `module-api-explorer` contract guard `api-coverage.json` |
| `flow-coverage` | `100 × taken / declared transitions` | never (WARN while a declared transition is untaken) | `module-flow` `flow-coverage.json` |
| `hygiene` | `100 − 5 × leftover rows − 10 × reporting problems − 5 × unused stubs − 5 × unmatched requests` | never (WARN) | run index, `module-mock` `mock-summary.json` |

- **Flaky** means failed and passed in the same run: a test listed twice
  (Gradle's test-retry) or with `<flakyFailure>` (Surefire's rerun). Without
  reruns there is nothing to see: `example-tests` applies
  `org.gradle.test-retry`, off by default — `-PtestRetries=1` reruns a failed
  test, the build stays green when the rerun passes, and the gate reports
  the test as flaky. It
  counts as passed for `functional` and as flaky for `stability`.
- **NOT MEASURED** is never a pass: a dimension without its input says so,
  and the overall status ignores it. Nothing measured at all fails the gate.
- **Overall** is the worst measured status; the mean score is for trend lines.

A module that is not used leaves its dimension NOT MEASURED with the reason
("no db-contract report (module-db-contract) in the run"); its reports are
read as JSON from the run index, so deleting a module never breaks this one.
`module-security` keeps its own accepted list and baseline; `security`
scores what it reports as `new`, and a security run that measured nothing is
NOT MEASURED, not a pass.

## How much was measured

`quality.md` says how many dimensions this run could judge — `measured: 5 of
10 dimensions — not measured: robustness, …` — so a green report built from
two dimensions does not pass for a green product. Make the ones that must be
there required; the gate then fails while one is NOT MEASURED, naming why:

```yaml
forge:
  quality:
    required: [functional, contracts, data]
```

Reports of other modules carry a `formatVersion` (absent means 1). One in a
layout this module does not know leaves its dimension NOT MEASURED with that
reason, rather than being read as zero findings.

## Which run is judged

- JUnit suites older than `forge.quality.results-not-before` are dropped and
  listed among the inputs — the results of a test task that did not run in
  this build. The `qualityGate` task sets it to the build's start when the
  test tasks run in the same build; a separate CI step passes
  `-PqualityResultsNotBefore=<instant>` (Gradle's `timestamp` is UTC,
  Surefire's local time is read in the machine's zone).

- By default the newest run index with a manifest — skipping one that holds
  only `module-quality`'s own report, which the gate's JVM leaves behind.
- A pipeline whose tests ran in several JVMs (`test`, `containersTest`,
  parallel forks) has one run directory each: list them,
  `forge.quality.run-id: "<unit-run>,<containers-run>"`, and they are judged
  as one. Setting `forge.reporting.artifacts.run-id` per job to a known value
  (the CI job id) makes them easy to name.
- Point `junit-results` at the test jobs' directories
  (`build/test-results/test`, `build/test-results/containersTest`) rather
  than at the gate's own task.

## Baseline: fail on what is new

Each dimension names its **issues** by stable keys — a failing test's id, a
flaky test's id, a reporting problem. Against the last accepted run
(`quality-baseline.json`) every issue is one of:

- **new** — the baseline did not have it: the gate fails on these;
- **accepted** — listed in `forge.quality.accepted` as `"<dimension> <issue>"`
  with a reason after ` #`;
- **known** — the baseline already had it;
- **resolved** — the baseline had it, this run does not.

```yaml
forge:
  quality:
    baseline-file: build/quality/baseline/quality-baseline.json
    accepted:
      - "functional io.acme.OrdersTest.refundsAnOrder()  # ORD-12, fixed in 2.4"
```

Without a baseline every issue is new. `run()` and `assertAcceptable()`
never write one: promoting a run is `qualityRunner.writeBaseline()` in a
reviewed change. Keep the file as a CI artifact of the accepted run or a
checked-in input. A FAIL without issue keys (a flaky rate over its limit
with no flaky test named) always fails.

## History: what one run cannot tell

A baseline is one accepted run; the history is the last runs, one small
entry each (`run-<run-id>.json`: dimension scores, test outcomes and
durations, operation p95 — no messages, no payloads). With
`forge.quality.history.dir` set, every `run()` reads the last `window`
entries and then adds its own — replacing the entry of the same run id, so a
gate run twice counts once — and drops the oldest beyond `keep`. In CI,
restore the directory from a cache or the previous run's artifact before the
gate and save it after; there is no database.

```yaml
forge:
  quality:
    history:
      dir: build/quality/history
      window: 10        # earlier runs read
      keep: 30          # entries kept
      min-runs: 3       # values a test or operation needs before its median counts
      revision:         # the commit tested; default GITHUB_SHA, CI_COMMIT_SHA, GIT_COMMIT
```

- **stability** also counts a test that failed and passed across the runs of
  the *same revision* — the code did not change, the outcome did. Without a
  revision nothing is compared across runs: a fix between two commits is
  not flakiness.
- **efficiency** compares each passed test with the median of its own last
  runs: slower means above median + `mad-factor` × MAD **and** `min-millis`
  above it, after dividing out the run's pace (the median ratio of this
  run's durations to those medians — a slower CI machine slows every test
  and is not a regression). API operations compare their p95 from
  `http-summary.json` the same way, without the pace and with
  `min-latency-millis`. A test with fewer than `min-runs` values is left
  out; with none at all the baseline comparison applies.
- **trends**: every measured dimension lists its score over the window; a
  PASS whose score fell in each of the last 3 runs becomes a WARN. A trend
  adds no issue, so it never fails the gate.
- **baseline hint**: after 5 runs without a FAIL, the report notes when the
  baseline is behind (issues it holds are resolved) or missing. It only
  says so: `writeBaseline()` stays an explicit, reviewed step.

An entry that cannot be read, or has another `formatVersion`, is skipped
and listed in the report's inputs, never a failed assessment.

## Configuration

```yaml
forge:
  quality:
    enabled: true                         # the gate fails closed without it
    junit-results: [build/test-results]   # searched recursively
    artifacts-root: build/testforge-artifacts
    run-id:                               # default: newest run with a manifest
    output-dir: build/quality
    max-flaky-rate: 0.02
    fail-on: [functional, stability]     # add contracts, data, robustness, authorization when they run
    efficiency:
      slowdown-factor: 2.0               # both limits at once, so a 10 ms test going to 90 ms is noise
      min-millis: 1000
      mad-factor: 3.0                    # with history
      min-latency-millis: 100            # with history, for API operations
    history:
      dir:                               # off unless set — see History
```

## Usage

A JUnit test in a CI step of its own, after the test jobs, with their
results and run index in place:

```java
@SpringBootTest
class QualityGateTest {
    @Autowired QualityRunner quality;

    @Test
    void theBuildIsAcceptable() {
        quality.assertAcceptable();   // run() reports without gating
    }
}
```

```
Quality gate failed on functional (3 tests: 2 passed, 1 failed, 0 skipped). See build/quality/quality.md
```

### As a Gradle task

`example-tests` ships the step ready to copy — a `Test` task that runs only
the `quality-gate`-tagged test, after the test tasks, pointed at their
results:

```bash
./gradlew :example-tests:test :example-tests:containersTest :example-tests:qualityGate \
    -PqualityRunIds=<test-run>,<containers-run> -PqualityBaseline=ci/quality-baseline.json \
    -PqualityHistory=ci/quality-history
```

`-PqualityRevision=<commit>` names the commit when the CI exposes none of
`GITHUB_SHA`, `CI_COMMIT_SHA`, `GIT_COMMIT`.

It sets `forge.quality.enabled=true`, the test tasks' JUnit XML directories
and `build/quality` as the output, is never up to date, and is not part of
`build`. Results of a test task that did not run in this build still count
— a CI workspace starts clean; locally run `clean` first or leave that task
out of `junit-results`.

`quality.md` and `quality.json` are registered in the current run index
under `module-quality`. Failure messages are their first line only; JUnit
XML is parsed without DTDs or external entities.

## Tests

`QualityRunnerTest` (Gradle and Surefire formats, flaky detection, newest
run index, the DTD refusal, the gate). Example: `QualityAssessmentExampleTest`.

Security coverage is part of the gate contract: `complete: false`, disabled or
unmeasured security reports, and `not_measured` coverage rows cannot produce a
clean score. With `fail-on: [security]`, incomplete reported evidence blocks the
gate even if a quality baseline or accepted list contains that issue. Observed
new critical/high findings still produce FAIL while coverage is incomplete.
An absent optional security report remains NOT MEASURED. Legacy reports without
`complete` require measured coverage to establish completeness.
