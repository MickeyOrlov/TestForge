# module-quality: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-quality`; how to *use* it lives in [README.md](README.md).

**Scope:** `QualityRunner` (run, gate), `JunitResults`, `RunIndex`, `QualityAssessor` dimensions, `quality.md` / `quality.json`. Spec: [docs/quality-security-spec.md](../docs/quality-security-spec.md) (Part A).

**May depend on:** `core` only. Other modules' output is read from files (JUnit XML, `manifest.json`, their own reports), never through their classes — a module that is deleted just leaves its dimension NOT MEASURED.

## Invariants (do not break)

- Read-only: the assessment never re-runs a test or changes an input file.
- The baseline changes only through `writeBaseline()`; `run()` and
  `assertAcceptable()` never write it. Issue keys are stable across runs
  (test ids, finding keys), never counters or timestamps.
- Every score is a formula over counted facts, printed with the score; a new
  dimension documents its formula in the README table.
- NOT MEASURED is never a pass; the gate fails closed when disabled or when
  nothing was measured.
- XML is parsed with DTDs disallowed and secure processing on.
- Reports carry test names and first lines of failure messages only.
- Another module's output is read from the run index by `source` /
  `category` and its file's JSON fields; a missing report is NOT MEASURED with
  the module that would produce it named. A field rename in a producer is a
  change to `ModuleDimensions` and its test in the same PR.

## Tests

Own: `QualityRunnerTest`, `ModuleDimensionsTest`. Example: `QualityAssessmentExampleTest`.

## Before you finish

- `./gradlew :module-quality:test :architecture-tests:test` is green, then `./gradlew build`.
- A new dimension → a case in `QualityRunnerTest` that computes its score
  by hand, the README table, and the spec's step table.
