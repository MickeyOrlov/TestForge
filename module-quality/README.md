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
| `stability` | `100 × (1 − flaky / executed)` | flaky rate above `max-flaky-rate` (WARN below it) | JUnit XML |
| `contracts` | `100 − 10 × mismatches` | any mismatch | `module-api-explorer` contract guard, `module-contract-monitor` |
| `data` | `100 − 50 × breaking − 20 × risky − 5 × unknown` | a breaking schema change (WARN on risky/unknown) | `module-db-contract` |
| `robustness` | `100 − 10 × finding groups` | a fuzz finding group nobody accepted | `module-api-fuzz` `findings.json` |
| `authorization` | `100 − 20 × violations` | a role got in, or was shut out, against the access matrix | `module-api-explorer` access matrix |
| `efficiency` | `100 × (compared − slower) / compared` | never (WARN): a test ≥ `slowdown-factor`× **and** ≥ `min-millis` slower than in the baseline | JUnit durations + baseline |
| `hygiene` | `100 − 5 × leftover rows − 10 × reporting problems` | never (WARN) | run index |

- **Flaky** means failed and passed in the same run: a test listed twice
  (Gradle's test-retry) or with `<flakyFailure>` (Surefire's rerun). It
  counts as passed for `functional` and as flaky for `stability`.
- **NOT MEASURED** is never a pass: a dimension without its input says so,
  and the overall status ignores it. Nothing measured at all fails the gate.
- **Overall** is the worst measured status; the mean score is for trend lines.

A module that is not used leaves its dimension NOT MEASURED with the reason
("no db-contract report (module-db-contract) in the run"); its reports are
read as JSON from the run index, so deleting a module never breaks this one.
The security dimension follows with step A4.

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

`quality.md` and `quality.json` are registered in the current run index
under `module-quality`. Failure messages are their first line only; JUnit
XML is parsed without DTDs or external entities.

## Tests

`QualityRunnerTest` (Gradle and Surefire formats, flaky detection, newest
run index, the DTD refusal, the gate). Example: `QualityAssessmentExampleTest`.
