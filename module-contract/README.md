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
- **`ContractDrafts.fromSamples(name, samples)`** — a first JSON Schema drafted
  from real payloads (shape only), to review and commit.
- **`ContractChanges.compare(v1, v2)`** — what a new schema version means for
  consumers of the old one: breaking, risky, safe, unknown.
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

## A first schema from real payloads

Writing a schema for an existing producer from scratch is where contracts
stall. Draft one from what it actually sends, review it, commit it:

```java
List<String> samples = kafkaProbe.findMessages(filter).stream().map(KafkaMessage::value).toList();
SchemaContract draft = ContractDrafts.fromSamples("partner-event", samples);
Files.writeString(Path.of("src/test/resources/contracts/partner-event.schema.json"), draft.schemaJson());
```

- A field is `required` when every sample that has its parent has it; types
  are the union seen (`integer` widens to `number`, `null` is added when a
  sample sent null); arrays get the merged shape of all their items.
- Shape only: no values, enums, examples or patterns are copied — samples
  may carry real data, and a contract pinned to one sample's values fails on
  the next. Tighten by hand: enums, formats, `additionalProperties: false`.
- A draft is a starting point, never regenerated on every run: a schema
  rebuilt from today's payloads accepts exactly the drift it should catch.

## What a new schema version means for consumers

Before the producer ships v2, compare it with the v1 consumers were built
against:

```java
List<ContractChange> changes = ContractChanges.compare(
        SchemaContract.fromResource("partner-event", "contracts/partner-event.v1.schema.json"),
        SchemaContract.fromResource("partner-event", "contracts/partner-event.v2.schema.json"));
ContractChanges.assertNoBreaking(changes);
```

```
BREAKING $.amount: no longer required: may be missing
BREAKING $.amount: may now arrive as [null] (was [number])
RISKY $.status: new values [pending]: a consumer switching over the old ones may not handle them
SAFE $.channel: new field (required)
```

- **BREAKING**: a required field removed or no longer required, a type that
  may now arrive (`null` added, `string` → `integer`).
- **RISKY**: new enum values, a removed optional field, a new field where the
  old schema had `additionalProperties: false`.
- **SAFE**: new fields, narrowed types, values no longer sent.
- **UNKNOWN**: `$ref`, `allOf`/`oneOf`/`anyOf`/`not` changed — reported, not
  guessed at.

Compared are `properties`, `required`, `type`, `enum`, `items` and
`additionalProperties`. Not consumer-driven contract testing: it judges the
schema change, not what any particular consumer reads.

## Agent notes

- The field DSL is FROZEN. Anything richer than required/optional/nullable +
  type goes through `SchemaContract` (JSON Schema) — do not extend the DSL.
- Schema violation codes are schema keywords ordered by a fixed priority;
  tests assert on them, keep them stable.
- Never reintroduce a dependency on module-kafka; composition happens in the
  test, not between modules.
