# module-flow: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-flow`; how to *use* it lives in [README.md](README.md).

**Scope:** `FlowRunner`: deterministic state-machine paths with guardrails, timed path reporting (`FlowTransition`) and step decorators. Plan: [docs/flow-layer-roadmap.md](../docs/flow-layer-roadmap.md).

**May depend on:** `core` only.

## Invariants (do not break)

- Deterministic: the same start state and steps give the same path.
- Guardrails (`timeout`, `max-transitions`, `max-visits-per-state`) fail with
  the path so far, never hang.
- Allure stays optional (`AllureFlowStepDecorator` uses a compile-only
  dependency).

## Tests

Own: `FlowRunnerArtifactTest`. Examples: `FlowRunnerTest`, `StateRecipePreparedDataTest`.

## Before you finish

- `./gradlew :module-flow:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
