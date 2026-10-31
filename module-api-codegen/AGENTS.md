# module-api-codegen: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-api-codegen`; how to *use* it lives in [README.md](README.md).

**Scope:** OpenAPI → Java records + typed `ApiClient` skeletons under `build/generated/testforge`.

**May depend on:** `module-api-discovery` (spec registry), `module-http` (generated clients use `ApiClient`).

## Invariants (do not break)

- Never write into a hand-maintained source directory.
- `oneOf`/`anyOf` → `Object` and enums → wire scalar in v1; changing that is a
  design decision, not a fix.
- Generated code is transport scaffolding; no business assertions in templates.
- Generated sources must compile: `GeneratedNamesCompileTest` holds the names
  Java will not take as they are. A new template construct gets a case there.
- Clients import models one by one, never with a wildcard.

## Tests

Own: `OpenApiJavaCodeGeneratorTest`, `GeneratedNamesCompileTest`, `ApiCodegenRunnerTest`. Example: `ApiCodegenExampleTest`.

## Before you finish

- `./gradlew :module-api-codegen:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
