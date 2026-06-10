# module-contract

Message contract validation for event/API payload drift checks.

## What's inside

- **`JsonContractValidator`** — validates a JSON document against a compact
  field contract.
- **`MessageContract` / `FieldRule`** — Java DSL for required, optional and
  nullable fields.
- **`ContractViolation`** — structured diffs with path, code and message.

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

**Growth boundary:** the day you need value patterns, enums, ranges or rules
over arbitrary array elements — do not extend this homemade DSL. Swap the
validator internals for JSON Schema (e.g. `com.networknt:json-schema-validator`)
and keep the `MessageContract`/`ContractViolation` API as a facade. The rule
set here is deliberately small so the replacement stays painless.
