# module-contract: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-contract`; how to *use* it lives in [README.md](README.md).

**Scope:** JSON payload *shape* validation: `MessageContract`/`FieldRule` (compact DSL) and `SchemaContract` (JSON Schema, networknt).

**May depend on:** `core` only. Kafka, HTTP and files call *into* this module, never the other way.

## Invariants (do not break)

- The `FieldRule` DSL is frozen. Patterns, enums, ranges → `SchemaContract`,
  never a new DSL keyword.
- This is shape validation, not consumer-driven contract testing. Do not add
  broker or transport code.
- Violations list every problem at once; do not stop at the first one.

## Tests

No own tests yet (gap). Examples: `JsonContractValidatorTest`, `ContractMonitorExampleTest`.

## Before you finish

- `./gradlew :module-contract:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
