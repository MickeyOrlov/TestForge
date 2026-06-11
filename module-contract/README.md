# module-contract

Message contract validation for event/API payload drift checks.

## What's inside

- **`JsonContractValidator`** — validates a JSON document against a compact
  field contract or a full JSON Schema.
- **`MessageContract` / `FieldRule`** — Java DSL for required, optional and
  nullable fields. Deliberately frozen at this size.
- **`SchemaContract`** — full JSON Schema contracts (networknt engine):
  patterns, enums, ranges, conditional branches, `additionalProperties`.
  Load from classpath with `SchemaContract.fromResource(name, location)`.
- **`ContractViolation`** — structured diffs with path, code and message.
  For schema contracts the code is the schema keyword (`required`, `type`,
  `enum`, ...), ordered by a fixed priority so the first violation is the
  most actionable one.
- **`ContractMappers.strict()`** — parser preset used by the validator:
  duplicate keys and trailing garbage are violations (`invalid-json`), not
  silently repaired input.

This is the neutral core for Kafka contract monitoring: a Kafka adapter can
poll messages from a topic, then pass each payload to this validator.

## Configuration

```yaml
forge:
  contract:
    fail-fast: false       # default
    max-violations: 100    # default
```

## Usage

```java
MessageContract contract = MessageContract.named("partner-status")
        .required("$.eventId", FieldType.STRING)
        .required("$.createdAt", FieldType.STRING)
        .required("$.payload.status", FieldType.STRING)
        .optional("$.payload.reason", FieldType.STRING)
        .build();

List<ContractViolation> violations = validator.validate(json, contract);
```

## What this is — and is not

This module detects **payload shape drift**: required/optional/nullable fields
and their JSON types. It is *not* consumer-driven contract testing — there is
no provider verification, no pact files, no schema evolution rules. If your
organization runs Pact or Spring Cloud Contract, use those for cross-team
contracts and keep this module for cheap QA-side drift checks.

**Growth boundary (realized):** the field DSL stays frozen at
required/optional/nullable + type — anything richer goes through
`SchemaContract` and the networknt JSON Schema engine. Do not extend the DSL;
write a schema.

```java
SchemaContract schema = SchemaContract.fromResource(
        "partner-event", "contracts/partner-event.schema.json");
validator.assertValid(payload, schema);
```
