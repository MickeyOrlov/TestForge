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
| `robustness` | `100 − 10 × finding groups` | a fuzz finding group nobody accepted | `module-api-fuzz` `findings.json`, Web Fuzzing Commons `report.json` (EvoMaster) |
| `authorization` | `100 − 20 × violations` | a role got in, or was shut out, against the access matrix | `module-api-explorer` access matrix |
| `efficiency` | `100 × (compared − slower) / compared` | never (WARN): with history, a test or an API operation's p95 above its median + `mad-factor` × MAD; without, a test ≥ `slowdown-factor`× **and** ≥ `min-millis` slower than in the baseline | JUnit durations, `http-summary.json`, history or baseline |
| `security` | `100 − 40 × critical − 20 × high − 5 × medium − 1 × low` (new findings) | a new critical or high finding (WARN on medium/low) | `module-security` `security-findings.json` |
| `reliability` | `100 × requests without 5xx / requests` | an operation answered 5xx or failed in transport — even where the test expected it | `module-http` `http-summary.json` |
| `db-queries` | `100 − 10 × flagged statement shapes` | a statement failed (swallowed or not) or wrote every row of a table; WARN on one over `forge.db.queries.slow-millis` | `module-db` `db-queries.json` |
| `api-coverage` | `100 × called / documented operations` | below `min-api-coverage` (WARN while any operation is uncalled) | `module-api-explorer` contract guard `api-coverage.json` |
| `flow-coverage` | `100 × taken / declared transitions` | never (WARN while a declared transition is untaken) | `module-flow` `flow-coverage.json` |
| `mutation` | `100 × detected / (detected + survived + not covered)` | below `mutation.min-score` (WARN while a mutant survives or is not covered) | PIT `mutations.xml` |
| `diff-coverage` | `100 × covered / measured changed lines` | below `diff-coverage.min-score` (WARN while a changed line is uncovered) | diff-cover `--format json:<file>` |
| `static-analysis` | `100 − 40 × critical − 20 × high − 5 × medium − 1 × low` | a critical or high finding (WARN on medium/low) | SARIF 2.1.0 from gitleaks, Trivy, Grype, Semgrep, CodeQL, SpotBugs |
| `load` | `100 × passed thresholds / thresholds` | a load run's native k6 thresholds failed; any other non-PASSED outcome is NOT MEASURED | `module-load` `report.json` |
| `hygiene` | `100 − 5 × leftover rows − 10 × reporting problems − 5 × unused stubs − 5 × unmatched requests` | never (WARN) | run index, `module-mock` `mock-summary.json` |

- **Failures** are counted as **assertion** (a check failed: `AssertionError`,
  opentest4j, AssertJ) or **error** (anything else thrown — usually the test
  or its environment: `[error]` after the test). Each carries a
  **fingerprint**, 12 hex digits over the exception type and the first three
  stack frames outside test libraries, without line numbers. Neither the
  trace nor its frames go into a report. Grouping failures into named
  categories is Allure's job (`categories.json`); this module does not
  repeat it.
- **Flaky** means failed and passed in the same run: a test listed twice
  (Gradle's test-retry) or with `<flakyFailure>` (Surefire's rerun). Without
  reruns there is nothing to see: `example-tests` applies
  `org.gradle.test-retry`, off by default — `-PtestRetries=1` reruns a failed
  test, the build stays green when the rerun passes, and the gate reports
  the test as flaky. It
  counts as passed for `functional` and as flaky for `stability`.
- **NOT MEASURED** is never a pass: a dimension without its input says so,
  and the overall status ignores it. Nothing measured at all fails the gate.
- **min-tests**: with `forge.quality.min-tests` set, `functional` FAILs while
  fewer tests executed — none at all included — so a filter or task that
  selected the wrong tests does not pass for a green run. The first fact
  names the count; it fails the gate while `functional` is in `fail-on` (the
  default) — whatever the baseline, `accepted` or a `max-new-issues` budget
  say about the tests that did fail. Only `dry-run` turns it into a note.
- **How to fix**: a FAIL or WARN dimension carries a `hint` — what to do
  about it, next to the facts that say what is wrong — in `quality.json`,
  `quality.md` and `quality-summary.md`.
- **Overall** is the worst measured status; the mean score is for trend lines.

A module that is not used leaves its dimension NOT MEASURED with the reason
("no db-contract report (module-db-contract) in the run"); its reports are
read as JSON from the run index, so deleting a module never breaks this one.
`module-security` keeps its own accepted list and baseline; `security`
scores what it reports as `new`, and a security run that measured nothing is
NOT MEASURED, not a pass.

## Mutation: how strong the tests are

Line coverage says a line ran; it does not say a test would notice it
change. PIT changes the code (a `>=` to a `>`, a return value to `null`)
and reruns the tests: a mutant no test fails on **survived**. `mutation`
reads what PIT wrote — the mutation engine stays PIT's — from
`mutations.xml` under `forge.quality.mutation.reports` (default
`build/reports/pitest`, `target/pit-reports`, searched recursively; one
per module, merged).

- Detected: KILLED, TIMED_OUT, MEMORY_ERROR, RUN_ERROR, as PIT counts them;
  NON_VIABLE mutants never compiled and are left out.
- Facts give the test strength too: detected among mutants a test covered.
- Issues are surviving mutants by method and mutator, without the line —
  it moves with every edit above it — so the baseline holds across edits.
- Run PIT incrementally on what the change touched (`withHistory`,
  `targetClasses` from the diff); a full run on every pull request is slow
  and 100 % is never the target: equivalent mutants cannot be killed.

```yaml
forge:
  quality:
    mutation:
      reports: [build/reports/pitest]
      min-score: 0.6       # FAIL below 60 % detected
```

## Diff coverage: the lines this change touched

A gate on overall coverage blocks every small fix in legacy code, or lets
coverage creep down a little per pull request. The lines a change touched
are what it can be held to. Intersecting `git diff` with a coverage report
is [diff-cover](https://github.com/Bachmann1234/diff_cover)'s job;
`diff-coverage` reads its JSON report:

```bash
diff-cover build/reports/jacoco/test/jacocoTestReport.xml \
    --compare-branch=origin/main --src-roots src/main/java --format json:build/reports/diff-coverage.json
```

```yaml
forge:
  quality:
    diff-coverage:
      report: build/reports/diff-coverage.json
      min-score: 0.8     # FAIL below 80 % of measured changed lines covered
      min-lines: 5       # fewer: NOT MEASURED, not a pass
```

- diff-cover calls a change without measured lines 100 % covered; here it
  is NOT MEASURED, as is one below `min-lines`, with the count in the fact.
- Facts name each file with its uncovered lines (`lines 12-14, 20`);
  issues are the files — a line number moves with every edit, the file
  does not. A diff is per pull request: run this with `fail-on` and
  `min-score`, not against a baseline.

## Scanners: SARIF from gitleaks, Trivy, Semgrep and others

Secret, dependency and code scanners all write SARIF 2.1.0; `static-analysis`
reads it. The scanners run in the project's build — this module never runs one
and keeps no pattern catalogue of its own.

```bash
gitleaks dir build/testforge-artifacts --redact --report-format sarif --report-path build/sarif/gitleaks.sarif
trivy fs --scanners vuln --format sarif --output build/sarif/trivy.sarif .
semgrep scan --config auto --sarif --output build/sarif/semgrep.sarif
./gradlew osvScan   # CycloneDX SBOM of the published modules -> OSV-Scanner -> build/sarif/osv-scanner.sarif
./gradlew :example-tests:spectralLint -PspectralSpecs=openapi.yaml -PspectralOutput=build/sarif  # OWASP API Top 10 on the API description, see tools/spectral
```

```yaml
forge:
  quality:
    sarif:
      reports: [build/sarif]       # files, or folders searched for *.sarif / *.sarif.json
      tool-severity:
        gitleaks: high             # gitleaks writes no level: a leaked secret is no warning
```

- One issue per tool, rule and file — plus the tool's own fingerprint when it
  gives a non-empty one — and never the line, so the baseline holds when code
  above moves: `Trivy CVE-2026-0001 pom.xml`, `gitleaks jwt artifacts/session.txt`.
  The file makes the finding CODEOWNERS-owned.
- Severity: the rule's `security-severity` (≥ 9 critical, ≥ 7 high, ≥ 4
  medium, else low) when it has one, otherwise the level — `error` high,
  `warning` medium (also a result without a level, as SARIF says), `note` low,
  `none` ignored — and never below the tool's `tool-severity` floor.
- A suppressed result (in source or external, not `rejected` / `underReview`)
  is counted as suppressed, not an issue; `baselineState: absent` is gone.
- `:osvScan` (root build, [gradle/osv.gradle](../gradle/osv.gradle)) builds the
  aggregate CycloneDX SBOM of the published modules' runtime classpaths with the
  CycloneDX Gradle plugin and scans it with OSV-Scanner 2.7.0 in its pinned container
  (`--pull=never`; pull it first). Each result's location is the vulnerable package
  version, so an issue reads `osv-scanner CVE-2026-59889
  com.fasterxml.jackson.core:jackson-databind@2.21.4`; severity is the advisory's CVSS.
  The scanner queries the OSV.dev API: package names and versions leave the machine,
  nothing else. Exit 0 (none) and 1 (found) are both complete scans; any other exit
  writes no report, so the dimension is NOT MEASURED.
- A missing, malformed (no `runs`, a run without `tool.driver.name`, a result
  without `ruleId`) or stale report is NOT MEASURED, never a clean pass.
- Facts name tool, rule, file and line only: a SARIF message or snippet may
  hold the very secret it reports.
- module-security's own artifact scan finds this run's credentials, gitleaks
  finds credential shapes; together they caught eight of eight planted leaks
  ([docs/toolbox-decisions.md](../docs/toolbox-decisions.md) §2).

## Fuzzers: what an API does when nobody wrote the test

Exploring an API is a fuzzer's job, not this module's. EvoMaster (and any
fuzzer writing [Web Fuzzing Commons](https://github.com/WebFuzzing/Commons)
`report.json`) generates the requests; `robustness` reads its faults next to
`module-api-fuzz`'s:

```bash
java -jar evomaster.jar --blackBox true --bbSwaggerUrl file://$PWD/openapi.yml \
    --bbTargetUrl http://localhost:8080 --outputFormat JAVA_JUNIT_5 \
    --outputFolder build/evomaster --maxTime 180s
```

```yaml
forge:
  quality:
    robustness:
      reports: [build/evomaster/report.json]
```

- One issue per operation and fault category, by the category's WFC name:
  `petclinic DELETE /owners/{ownerId} HTTP_STATUS_500`,
  `petclinic POST /owners SECURITY_XSS` (1xx HTTP semantics, 2xx schema, 3xx
  security; an unknown code is `WFC_<code>`). The fact names the generated
  test that shows it.
- A configured report that is missing, not WFC, has a fault without an
  operation or a category, or is older than
  `results-not-before` leaves `robustness` NOT MEASURED: a fuzzer that did not
  run this time found nothing.
- A black-box fuzzer writes: run it against a disposable environment, never a
  shared one.

## One name per operation

Every report names an API operation its own way — the traffic summary by URL
(`GET /petclinic/api/owners/{n}`), a WFC fuzzer by `GET:/petclinic/api/...`,
the contract guard by template. With the contract guard on, its
`api-coverage.json` lists every documented operation, and `reliability`,
`efficiency` and `robustness` name operations as it does:
`<spec> <METHOD> <documented template>` (`petclinic GET /owners/{ownerId}`).
A base path in front is matched without configuration; a longer template wins
(`/v2/owners` over `/owners`). An operation the document does not know keeps
its own name, and so does one that two documents match equally well
(`billing GET /health`, `orders GET /health`): a guess would merge two APIs'
issues and history. `contracts` keys carry the same `<METHOD> <template>`, so one
component pattern (`"*GET /owners/{ownerId}*"`) covers an operation in every
dimension.

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

- PIT, diff-cover and WFC reports have no timestamp of their own: with
  `results-not-before` set, a report file written before it is an earlier
  build's and its dimension is NOT MEASURED, naming the file and its time.

- JUnit suites older than `forge.quality.results-not-before` are dropped and
  listed among the inputs — the results of a test task that did not run in
  this build. The `qualityGate` task sets it to the build's start when the
  test tasks run in the same build; a separate CI step passes
  `-PqualityResultsNotBefore=<instant>` (Gradle's `timestamp` is UTC,
  Surefire's local time is read in the machine's zone).

- By default the newest run index with a manifest — skipping one that holds
  only `module-quality`'s own report, which the gate's JVM leaves behind.
- The gate's JVM writes a run index of its own, so it must not share the
  tests' run id: when its context closes it would replace that run's
  `manifest.json` with its own two reports, and a second gate on the run
  would judge 3 dimensions instead of 13. A `forge.reporting.artifacts.run-id`
  set once for the whole CI job (the job id) does exactly that; the
  `qualityGate` task gives the gate the run id `quality-gate`.
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
      - "efficiency GET /reports  # until 2026-12-01, REP-112"
```

`until <date>` in the comment makes an acceptance expire: from the day
after, the issue counts as new again and the report notes it.

**Quarantine** is an accepted failing test pinned to its fingerprint
(`functional` only): `@<fingerprint>` after the test id, copied from its
`failed:` fact. The test keeps running; the gate lets it through only while
it fails the same way, and a different failure — another exception, another
place — is new, with a note naming both fingerprints. The pin outranks the
baseline: a test the baseline already had is still new once it fails
differently. Every failed attempt counts — a retry (Gradle's second
`<testcase>`, Surefire's `<rerunFailure>`) that fails differently gives the
test both fingerprints, `a1…,b2…`, and no single pin matches it:

```yaml
forge:
  quality:
    accepted:
      - "functional io.acme.PayTest.pays @1a2b3c4d5e6f  # QUAR-7 sandbox timeouts, until 2026-11-30"
``` An accepted
entry that matches no issue of the run is noted too — delete it once that
holds, or it will silently cover the next regression.

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
  `min-latency-millis`, against runs not judged FAIL only — a broken run may
  answer errors fast or time out slow, and its p95 is not the usual (the
  first fact says how many were left out). A test with fewer than `min-runs` values is left
  out; with none at all the baseline comparison applies.
- **switching and stabilized**: across the whole window, revisions
  included, `stability` lists tests whose outcome switched between passing
  and failing at least 3 times (`switching: T — passed / failed 4 times in
  7 runs`), and tests that were flaky earlier but passed the last 5 runs
  (`stabilized: …`). Across revisions a switch may be a fix, so these are
  leads: they add no issue and change no score.
- **trends**: every measured dimension lists its score over the window; a
  PASS whose score fell in each of the last 3 runs becomes a WARN. A trend
  adds no issue, so it never fails the gate.
- **baseline hint**: after 5 runs without a FAIL, the report notes when the
  baseline is behind (issues it holds are resolved) or missing. It only
  says so: `writeBaseline()` stays an explicit, reviewed step.

An entry that cannot be read, or has another `formatVersion`, is skipped
and listed in the report's inputs, never a failed assessment.

## Who owns it: labels and components

`quality.json`, the history and the metrics carry the run's **labels**:
`revision`, `branch` and `job` from the CI's own variables (`GITHUB_SHA` /
`CI_COMMIT_SHA` / `GIT_COMMIT`, `GITHUB_REF_NAME` / `CI_COMMIT_REF_NAME` /
`GIT_BRANCH` / `BRANCH_NAME`, `GITHUB_RUN_ID` / `CI_JOB_ID` / `BUILD_NUMBER`),
then `forge.quality.run-labels` on top.

**Components** group issues by owner. Each issue key — a test id, an
operation (`demo GET /orders/{id}`), a table (`BREAKING COLUMN_REMOVED
ORDERS.AMOUNT`) — goes to the first component with a matching pattern (`*` is
any text); what matches none is listed as `(unassigned)`, so a gap in the
mapping shows. One component per service gives each service its own status.

```yaml
forge:
  quality:
    run-labels:
      environment: staging
    components:
      orders:
        owner: team-orders
        match: ["io.acme.orders.*", "* /api/v1/orders*", "*ORDERS*"]
      billing:
        owner: team-billing
        match: ["io.acme.billing.*", "* /api/v1/invoices*"]
```

A component's status is the worst status of the dimensions it has issues in;
the report lists its issues and how many are new.

**CODEOWNERS** saves a second owner mapping. With
`forge.quality.codeowners: .github/CODEOWNERS`, an issue no component
matched goes to the owners of the file its key points at — a test or a
mutated class (looked up once among the repository's `src/*/java|kotlin|…`
sources; `build/`, `target/` skipped), or a changed file from
`diff-coverage`. As on GitHub the last matching rule wins and a rule
without owners leaves the file unowned; the owners are the component's
name. Operations and tables point at no file: map them in `components`.

## Gate policy per dimension

`fail-on` fails on a FAIL with a new issue. `forge.quality.gate` adds per
dimension:

```yaml
forge:
  quality:
    gate:
      stability: { max-new-issues: 2 }   # a budget: up to 2 new flaky tests pass, the 3rd fails
      api-coverage: { min-score: 80 }    # below 80 fails, even at WARN
```

With `max-new-issues` a `fail-on` FAIL passes while its new issues stay within
the budget, and any status fails beyond it. A NOT MEASURED dimension is not
judged here — list it in `required`.

### Dry run: watch a rule before enforcing it

```yaml
forge:
  quality:
    fail-on: [functional, stability, contracts]
    required: [contracts]
    gate:
      contracts: { dry-run: true, min-score: 90 }
```

Nothing the gate would fail on for a dry-run dimension — `fail-on`,
`required`, `min-score`, `max-new-issues`, an incomplete security run —
fails it. The report notes each instead (`dry run — the gate would fail on
contracts (…)`), in `quality.md` and in `quality-summary.md`, so a team sees
on its pull requests how often the rule would have fired. Remove `dry-run`
once it fires only on real regressions. Other dimensions stay enforced.

## Exports

Every report carries the **gate verdict** — the same rules `assertAcceptable()`
enforces — so a reader never has to work it out: `gate` in `quality.json`
(`verdict`, `failures`, `dryRun`), `- gate: **PASS**` in `quality.md` (with
"what fails is known or accepted, nothing new" when overall is FAIL), the
summary's first line `### Quality gate: PASS · overall FAIL`, and
`testforge_quality_gate_passed` in `quality.prom`. A score is never 100 while
something failed: 252 of 253 is 99.

Each dimension's part in the gate is a column of the tables and
`gate.dimensions` in `quality.json`: **enforced** (`fail-on`, `required` or a
`gate` limit), **dry-run**, or **report only** — a FAIL there is information
and fails nothing. A gate failure names what is new first, the sample after
it: `contracts (2 new: api GET /oops …, … — 26 API responses checked …)`.

Beside `quality.md` and `quality.json` in `output-dir`:

- `quality-summary.md` — overall, the dimensions that are not PASS with their
  first new issues, components with new issues: short enough for a pull
  request comment;
- `quality.prom` — OpenMetrics text (`testforge_quality_score`,
  `testforge_quality_status`, `testforge_quality_issues{kind="new"|"all"}`,
  `testforge_quality_component_new_issues`, `testforge_quality_run_info` with
  the labels) for a push gateway or a textfile collector. `revision`, `job`
  and `run_id` take a new value every run — a new series each time, kept
  until retention — so they stay out of it unless
  `forge.quality.metrics.run-ids: true`; the report and the history keep
  them. `branch` stays: mind it when every pull
  request branch reaches the metrics;
- `quality.schema.json` — JSON Schema of `quality.json` (`formatVersion` 1).

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
    min-tests:                           # optional: functional FAILs while fewer tests executed
    mutation:
      reports: [build/reports/pitest, target/pit-reports]  # PIT mutations.xml, searched recursively
      min-score:                         # optional, 0..1: mutation FAILs below this share (default: WARN only)
    diff-coverage:
      report: build/reports/diff-coverage.json  # diff-cover --format json:<file>
      min-score:                         # optional, 0..1: diff-coverage FAILs below this share
      min-lines: 1                       # fewer measured changed lines: NOT MEASURED
    codeowners:                          # e.g. .github/CODEOWNERS: owners of issues no component matched
    sarif:
      reports: []                        # SARIF 2.1.0 files or folders; see Scanners
      tool-severity: {}                  # e.g. {gitleaks: high}
    load:
      reports: []                        # module-load report.json files or folders, beside the run index
    release:
      required: [functional, contracts, security, load]  # testforge-quality release; see Release
      limits: {}                         # per dimension: {max-issues: n, min-score: n}
    metrics:
      run-ids: false                     # revision, job, run_id as quality.prom labels (a series per run)
    min-api-coverage:                    # optional, 0..1: api-coverage FAILs below this share (default: WARN only)
    efficiency:
      slowdown-factor: 2.0               # both limits at once, so a 10 ms test going to 90 ms is noise
      min-millis: 1000
      mad-factor: 3.0                    # with history
      min-latency-millis: 100            # with history, for API operations
    history:
      dir:                               # off unless set — see History
```

## Load: k6 runs judged by their own thresholds

`load` reads the `report.json` every `module-load` run writes — from the run
index, and from `forge.quality.load.reports` (files, or folders holding them):

- PASSED is PASS, THRESHOLDS_FAILED is FAIL; INVALID_RESULT, EXECUTION_ERROR,
  TIMED_OUT and CANCELLED leave the dimension NOT MEASURED with the run's own
  detail — a load run that measured nothing valid is never a pass.
- One issue per failing scenario, keyed by its script's SHA-256
  (`load script f76e985538f9`): the same scenario failing again is the same
  issue. Facts: requests, actual rate, p95, error rate, dropped iterations,
  thresholds passed of total.
- The thresholds are k6's: this module neither re-computes timings nor adds
  limits of its own.

## Release: can this build ship

The gate asks "did this change make things worse" — new issues against a
baseline. A release asks something else:

```bash
testforge-quality release --config quality.yml     # 0 READY · 1 NOT_READY · 2 UNKNOWN · 64 usage
```

```yaml
forge:
  quality:
    release:
      required: [functional, contracts, security, load]   # the default
      limits:
        reliability: { max-issues: 0 }
        api-coverage: { min-score: 80 }
```

- **Absolute.** An issue the baseline already knew still counts. A dimension
  blocks when it is `required`, in `fail-on` or has a release limit, and it
  FAILs with an open issue — or breaks its limit.
- **UNKNOWN, not READY,** while a `required` dimension is NOT MEASURED; each is
  named with the reason.
- **Accepted risks are listed, not hidden:** every `accepted` entry whose issue
  is present appears with its owner (components / CODEOWNERS), the reason
  after `#` and its `until`. A FAIL whose every issue is accepted ships as a
  risk; an expired acceptance blocks.
- Writes `release.md` (first line `### Release: NOT_READY — …`), `release.json`
  and `release.schema.json` beside the quality report. The everyday `check`
  gate is unchanged.

On petclinic, with two contract mismatches the baseline knew, the gate passed
and `release` said NOT_READY — the point of the second question.

## The command: `testforge-quality`

The same gate without a Spring test, for a CI step, a person or an agent that
runs the build and acts on the result:

```bash
./gradlew :module-quality:installDist        # build/install/testforge-quality/bin/testforge-quality
testforge-quality check --config quality.yml [--set min-tests=50]... [--json]
testforge-quality baseline --config quality.yml   # promote this run, in a reviewed change
```

- `quality.yml` holds the `forge.quality` tree an `application.yml` would;
  relative paths are relative to where the command runs. `--set` overrides
  one key (`forge.quality.` may be left out). A list given with `--set`
  replaces the file's list whole: write it comma-separated
  (`--set fail-on=functional,contracts`) or from index 0 on (`accepted[0]=…`);
  `fail-on[1]=…` alone leaves a hole and is refused.
- `baseline` refuses a run that measured nothing or missed a `required`
  dimension: the accepted baseline stays as it was, exit 2.
- `check` prints `quality-summary.md` (`--json`: `quality.json`) and exits
  **0** when the gate passes, **1** when it fails (`gate.failures` says
  why), **2** when nothing could be measured or an input cannot be read,
  **64** on a wrong command line.
- It registers nothing in a run index: it only reads what the build left.
- Java 21, like the rest of TestForge (`JAVA_HOME`).

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

For GitHub Actions the whole job is in
[docs/examples/quality-gate.github-actions.yml](../docs/examples/quality-gate.github-actions.yml):
it restores the history from the cache, runs the tests with the run index
on, runs the gate with the start instant as `-PqualityResultsNotBefore`,
saves the history, uploads the report and puts `quality-summary.md` on the
pull request.

It sets `forge.quality.enabled=true`, the test tasks' JUnit XML directories,
`build/quality` as the output and a run id of the gate's own, is never up to
date, and is not part of `build`. Copied into a project, its task paths
(`:example-tests:test`, `:example-tests:containersTest`) become that project's
test tasks. Results of a test task that did not run in this build still count
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
