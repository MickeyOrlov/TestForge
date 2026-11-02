# module-quality in the field: spring-petclinic-rest

November 2026. The quality module was run against someone else's open-source
application the way an adapting project would use it: TestForge from its
published artifacts (`publishTestForgeLibraries`), with no access to its
sources.

## Setup

| What | How |
|---|---|
| Application | [spring-petclinic-rest](https://github.com/spring-petclinic/spring-petclinic-rest) `d350090`: Spring Boot 4.1, OpenAPI 3 (`openapi.yml`), H2, 244 tests of its own (Maven Surefire), JaCoCo |
| Mutations | PIT 1.30.1 + pitest-junit5-plugin 1.2.3: `model.*`, `rest.validation.*`, `ClinicServiceImpl` against three test classes (Spring tests are slow under mutation) |
| Diff coverage | diff-cover 11.0.0 over `jacoco.xml` against `origin/master` |
| TestForge harness | a separate Gradle project: 8 functional tests through `ApiClient` with the contract guard (`record`), the explorer over every GET, reporting into the run index; a `qualityGate` step judges it all together |
| State between runs | `quality-state/`: baseline and history (as a CI cache would keep them) |

Gate configuration: `fail-on: [functional, stability, contracts, reliability]`,
`required: [functional, contracts]`, `min-tests: 50`.

## Runs

| # | Scenario | Gate | What it showed |
|---|---|---|---|
| r0 | Clean, no baseline | FAIL | 2 real contract mismatches and a 500 on `/oops` — see below |
| r1 | `writeBaseline()` | — | baseline written as an explicit step |
| r2 | Same code | PASS | known issues no longer fail the gate |
| r3 | Same code, a test red (`DOGFOOD_FLAKY=1`) | FAIL | a new failure with fingerprint `edd72cea0c2b` |
| r4 | Same code, the test green again | PASS | `stability` WARN: "flaky across runs" on one revision |
| r5 | A branch with a regression and new untested code | FAIL | petclinic's own test `404 ≠ 200`; diff coverage 1 of 8 lines |
| r6 | The failure quarantined by fingerprint | PASS | `accepted (1)` |
| r7 | Quarantine pinned to a wrong fingerprint | FAIL | note "accepted for fingerprint 000…, failed with ea5b…" |
| r8 | CODEOWNERS in the petclinic repository | — | test → `@petclinic/qa`, mutants and diff → `@petclinic/model` / `core` |

## What the module can do — confirmed on a real project

- **Merges foreign and own results.** petclinic's 244 Maven Surefire tests and
  the harness's 8 Gradle tests are read as one run (253 with the flaky test).
- **Finds real contract defects.** The contract guard over 26 responses:
  - `GET /oops` answers 500, which the document does not declare
    (`UNDOCUMENTED_STATUS`);
  - `GET /owners/{id}` for an unknown owner answers 404 without a
    `Content-Type`, while springdoc promises a body
    (`UNEXPECTED_CONTENT_TYPE`) — exactly the case the explorer's README
    describes.
- **Reliability does not trust the test.** `/oops` answers 500, the test
  expects 500, the dimension still FAILs: a server error is a defect.
- **API coverage is honest.** 46 %: 21 write operations without a single
  test, listed.
- **PIT is read as it is.** A real `mutations.xml` (attributes in single
  quotes) — 105 mutants, 6 survived, test strength 92 % on covered code.
- **diff-cover is read in both forms.** The deprecated `--json-report` and the
  new `--format json:<file>` give the same layout. The new untested method —
  `Owner.java lines 141, 143-148`.
- **Baseline, flaky detection, fingerprints and quarantine work as designed**,
  including the refusal of a wrong fingerprint.
- **Efficiency** compares 252 tests with their medians after three runs and
  divides out the machine's pace (×1.09).
- **CODEOWNERS** finds test and mutated-class files on a real
  `src/main|test/java` layout.
- **Several Spring contexts** in one JVM write `contract-guard.json` and
  `contract-guard-2.json` — both are read.

## What the module cannot do — limits by design

- **It sees only traffic through TestForge.** petclinic's 244 MockMvc tests
  count in `functional`, but their HTTP calls never reach `contracts`,
  `reliability` or `api-coverage`. The contract guard works on `ApiClient`
  only.
- **A black box without its database.** The application is a separate
  process: `db-queries`, `data` and leftover rows in `hygiene` are NOT
  MEASURED. `module-db` / `module-db-contract` need access to the
  application's database.
- **Not run here:** fuzzing (`robustness`), security, the access matrix
  (`authorization`), `flow-coverage` — these are separate modules the harness
  did not include.
- **Production signals** (escape rate, change failure rate) are outside the
  model, as expected.
- **First runs have no history.** `efficiency` by medians starts with the
  third run; before that, the baseline or NOT MEASURED.
- **PIT on a Spring application is slow**; choosing mutation targets
  (`targetClasses` from the diff) stays with the project — the module only
  reads the report.

## What needed work — found in the field

| # | Problem | Seen in | Proposal | Size |
|---|---|---|---|---|
| D1 | **One operation, three keys.** `GET /petclinic/api/owners/{n}` (reliability), `api GET /owners/{ownerId}` (contracts), `petclinic GET /owners/{ownerId}` (api-coverage). Components and CODEOWNERS cannot bring them together; one defect does not read as one | r8: 23 issues `(unassigned)` | one operation key `<spec> <METHOD> <template>`; `reliability` matches the path against the document, as the contract guard does | M |
| D2 | **The summary says FAIL while the gate passes.** After a baseline, `quality-summary.md` starts with "Quality: FAIL" though the gate passed | r2, r6 | a gate verdict line (`gate: PASS — 0 new issues in fail-on`) in `quality.md`, the summary and `quality.json` | S |
| D3 | **A score of 100 at FAIL.** 252 of 253 → 99.6 % → rounded to 100; the trend reads `100 → 100` with a failed test | r3, r5 | no 100 while anything failed | S |
| D4 | **Stale PIT and diff-cover reports are read silently.** JUnit has `results-not-before`, they do not; a report of an earlier revision would be judged as this one. They are not listed under "Inputs" either | r5 | report files under "Inputs" with their time; one older than `results-not-before` is NOT MEASURED with the reason | S |
| D5 | **Which dimensions decide the gate is not visible.** `diff-coverage` FAILs outside `fail-on` and fails nothing, and `dry-run` on it says nothing — a reader cannot tell why that FAIL "does not count" | r5 | a Gate column in the table (`enforced` / `dry-run` / `report only`) | S |
| D6 | **Hints are not situational.** `functional` always mentions `min-tests`, even when enough tests ran | r3–r7 | a hint built from the facts: `min-tests` only when too few ran | S |
| D7 | **Uninformative facts.** diff-cover writes `report_name: "XML"` → "(XML)"; the gate message takes the first fact ("26 API responses checked…") instead of the count of mismatches | r0, r5 | do not print `XML`; the gate message leads with the problem, not the sample | S |
| D8 | **The README recommends a deprecated option.** diff-cover 11 warns that `--json-report` is deprecated in favour of `--format json:<file>` | r0 | update the example | S |

D1 mattered most: without it owners, components and counting "one defect
across several dimensions" do not work on a real API. D2–D8 were polish of
the report, an hour or two each.

**Status:** all eight are fixed. D1, D2, D3, D4, D8 and the "(XML)" part of
D7 came with the `testforge-quality` command and WFC reading; a re-run of the
command on the petclinic data with EvoMaster's report gives 81 fault groups
and `reliability` → `petclinic GET /oops`. D5 (the Gate column), D6 (the
`min-tests` hint only when too few tests ran) and the rest of D7 (gate
messages lead with what is new) followed in the next commit.

## Are we reinventing the wheel

Checked after the runs (web search, November 2026):

- **Exploring an application — exists, and stronger than ours.** EvoMaster
  (LGPL, v6.2.0, September 2026) builds request chains from OpenAPI by
  itself, looks for 500s, schema mismatches and access-policy violations, and
  generates JUnit tests; likewise Schemathesis (already in `module-api-fuzz`),
  RESTler, ZAP. `module-api-explorer` covers only GET — a partial wheel.
  Decision: an external tool explores, and its results are inputs to the
  quality module.

  **EvoMaster 6.2.0 against petclinic** (black box, 3 minutes,
  `--bbSwaggerUrl` on `openapi.yml`):

  | | `module-api-explorer` (GET only) | EvoMaster |
  |---|---|---|
  | HTTP calls | 26 (with the functional tests) | 1879 |
  | Operations answering 2xx | 15 of 37 | 16 of 37 |
  | Operations answering 500 | 1 (`/oops`) | 41, including `DELETE /owners/{id}`, `DELETE /pets/{id}`, `GET /v2/owners`, `GET /v2/pets`, `OPTIONS` on most paths |
  | Operations with a response schema violation | 2 | 36 (content type, `allOf`, empty body, undocumented status) |
  | Tests for the findings | — | 134 JUnit 5 (`EvoMaster_faults_Test.java`, `…_successes_Test.java`) |

  EvoMaster's write requests change the application's data (petclinic's H2
  is in memory and resets on restart); never run it like this against a
  shared environment.

  It writes its result as `report.json` in the
  [Web Fuzzing Commons](https://arxiv.org/pdf/2509.01612) format
  (`schemaVersion` 0.7.0): `faults.foundFaults[]` with `operationId`
  (`POST:/petclinic/api/owners`), `testCaseId` and `faultCategories[].code`,
  plus `problemDetails.rest` with covered endpoints and statuses. The codes
  are WFC's `DefinedFaultCategory` — 100 `HTTP_STATUS_500`, 120
  `HTTP_INVALID_LOCATION`, 200 `SCHEMA_INVALID_RESPONSE`, 301 `SECURITY_XSS`
  (on `POST /owners` and `POST /pettypes`). A ready input for the quality
  module — `robustness` now reads it next to `module-api-fuzz`'s
  `findings.json` — and yet another operation key form (`METHOD:/full/path`),
  one more reason for D1.
- **A judge over heterogeneous reports — partly exists.**
  [uhafner/quality-monitor](https://github.com/uhafner/quality-monitor) (MIT,
  active; by the author of Jenkins warnings-ng and coverage) reads JUnit,
  JaCoCo, PIT and 100+ static-analysis formats, keeps thresholds and comments
  on pull requests — but it is a GitHub/GitLab Action, without a "new versus
  known" baseline, quarantine, flaky history, or runtime signals (contracts,
  5xx, API coverage).
  [Allure 3](https://allurereport.org/docs/quality-gate/) has
  `allure quality-gate` with `maxFailures`, `minTestsCount`, `successRate`,
  over its own results only and not compatible with retries. The Jenkins
  Coverage plugin has a baseline over changed files but needs a server.
- **Conclusion.** The module's own niche: baseline and quarantine by
  fingerprint, history, TestForge's runtime dimensions, NOT MEASURED, no
  server. Coverage and mutation are broader in quality-monitor; worth
  checking whether to read its `metrics.env` / model as an input instead of
  keeping our own parsers.

## Repeat it

The harness lives outside the repository (`~/Documents/quality-dogfood`): a
petclinic clone with PIT in its `pom.xml`, `harness/`, and
`run.sh <run-id> <revision> [-P…]`, which runs the harness suite against the
running application and then `qualityGate`. `quality.yml` there is the same
configuration for `testforge-quality check`.
