# module-data

Small helpers for data-heavy tests.

## What's inside

- **`RunUniqueValues`** — per-JVM registry that prevents generated values from
  being reused in parallel scenarios.
- **`TemplateRenderer`** — expands `%{variable}%` placeholders with cycle and
  missing-variable diagnostics.
- **`Generators`** — mask presets (`guid`, `phone(prefix, digits)`, `numeric`,
  `alphanumeric`) meant to be passed to `RunUniqueValues.generate(...)`.
- **`@Prepared` / `PreparedDataPool` / `PreparedDataProvider`** — fixture-style
  injection of prepared domain objects (see below).

The module does not ship **domain** Object Mothers (order payloads, user
profiles, product cards). Add those in the adapted project as small static
factories or builders; use `RunUniqueValues` + `Generators` for uniqueness and
masks, `TemplateRenderer` for payloads that reference scenario variables.

## Configuration

```yaml
forge:
  data:
    max-template-depth: 10
```

## Usage

```java
String phone = uniqueValues.generate("phone", Generators.phone("+8499", 7), 20);
String id = uniqueValues.generate("requestId", Generators.guid(), 10);
String body = templateRenderer.render("{\"id\":\"%{requestId}%\"}", variables);
```

## Prepared objects: `@Prepared` + pool

The most expensive part of deep E2E tests is bringing a domain object into
the right state ("an active agreement", "a verified client"). The pool moves
that cost out of the test body:

```java
@SpringBootTest
@ExtendWith(PreparedParameterResolver.class)
class RepaymentTest {

    @Test
    void earlyRepayment(@Prepared(tags = "active") DemoAgreement agreement) {
        // agreement came from the pool, or was built on a cold miss —
        // the test does not know and does not care
    }
}
```

How it fits together:

- **`PreparedDataProvider<T>`** — THE adaptation point: implement it to drive
  the product API into the requested state (a `module-flow` run is the
  natural body of `prepare(tags)`). When that setup is reusable, prefer
  `module-state`: write a `StateRecipe<T, S>` and expose it through
  `StatePreparedDataProvider`.
- **`PreparedDataPool`** — stock variants up front with
  `preload(type, tags, count)` (suite hook or scheduled job); `acquire` falls
  back to on-the-spot preparation on a cold miss. Objects are handed out
  exactly once — a used domain object is dirty by definition.
- **`PoolEventListener`** — hooks (`onAcquired`/`onPrepared`/`onExhausted`)
  for metrics and background refill, default no-op.

## Agent notes

- `PreparedDataProvider<T>` is THE adaptation point — its `prepare(tags)`
  should drive the product API (typically through `module-state` +
  `module-flow`), never fabricate rows directly in the DB unless the project
  explicitly allows it.
- Pool objects are handed out exactly once; there is no release on purpose.
- Uniqueness comes from `RunUniqueValues` + `Generators`; do not invent
  ad-hoc random suffixes in tests.
