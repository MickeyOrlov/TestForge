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
| `hygiene` | `100 − 5 × leftover rows − 10 × reporting problems` | never (WARN) | run index |

- **Flaky** means failed and passed in the same run: a test listed twice
  (Gradle's test-retry) or with `<flakyFailure>` (Surefire's rerun). It
  counts as passed for `functional` and as flaky for `stability`.
- **NOT MEASURED** is never a pass: a dimension without its input says so,
  and the overall status ignores it. Nothing measured at all fails the gate.
- **Overall** is the worst measured status; the mean score is for trend lines.

More dimensions (contracts, data, robustness, efficiency, security) and a
baseline comparison follow the spec's steps A2–A4.

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
    fail-on: [functional, stability]
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
