# module-data

Small helpers for data-heavy tests.

## What's inside

- **`RunUniqueValues`** — per-JVM registry that prevents generated values from
  being reused in parallel scenarios.
- **`TemplateRenderer`** — expands `%{variable}%` placeholders with cycle and
  missing-variable diagnostics.
- **`Generators`** — mask presets (`guid`, `phone(prefix, digits)`, `numeric`,
  `alphanumeric`) meant to be passed to `RunUniqueValues.generate(...)`.
- **`Factory`** — test objects and payloads with readable, unique defaults,
  overridable per field, with named traits (see below).
- **`@Prepared` / `PreparedDataPool` / `PreparedDataProvider`** — fixture-style
  injection of prepared domain objects (see below).
- **`DataIsolation` / `DataIsolationExtension`** — run and per-test tags inside
  generated values, so parallel tests and runs never see each other's data.
- **`Cleanup` / `CleanupExtension`** — undo what a test created (see below).

The module does not ship **domain** Object Mothers (order payloads, user
profiles, product cards). Declare those in the adapted project with `Factory`;
`TemplateRenderer` stays the tool for text payloads that reference scenario
variables. The plan for the module is [docs/data-layer-roadmap.md](../docs/data-layer-roadmap.md).

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

## Factories: readable defaults, override what the test is about

Declare once what a valid object looks like; a test overrides only the field
its scenario is about:

```java
static final Factory<Customer> CUSTOMERS = Factory.forRecord(Customer.class)
        .field("name", f -> f.label())                         // customer-1, customer-2, ...
        .field("email", f -> f.get("name") + "@example.test")   // follows an overridden name
        .field("phone", f -> f.unique("phone", Generators.phone("+1555", 7)))
        .field("tier", "standard")
        .trait("vip", c -> c.with("tier", "vip"));

Customer anna = CUSTOMERS.as("vip").with("name", "anna").build();
List<Customer> batch = CUSTOMERS.build(5);

static final Factory<Map<String, Object>> ORDERS = Factory.map("order")
        .field("item", "book")
        .field("quantity", 1);
Map<String, Object> invalid = ORDERS.without("quantity").build();  // the key is gone
```

- `forRecord(type)` builds a record through its canonical constructor; every
  component needs a default or an override, and a value of the wrong type
  names the component. `map(name)` builds a payload map in declaration order.
  `of(name, assembler)` covers anything else.
- Derived fields (`f -> ...`) are computed per build, once, and see overrides;
  a cycle between them fails with the path.
- `with`, `without` and `as` return a new factory, so a `static final`
  declaration is safe in parallel tests. An unknown field or trait fails with
  the known ones.
- `f.label()` is the factory name plus a sequence shared by every copy, so
  labels never repeat in a JVM. `f.unique(type, generator)` draws from a
  `RunUniqueValues` registry — the factory's own by default; pass the bean with
  `.uniqueValues(registry)` to share it with the rest of the suite.

## Isolation: only ever see your own data

On a shared environment a list endpoint returns everyone's data. Put a tag
into a value the test sends anyway and filter by it:

```java
@ExtendWith({DataIsolationExtension.class, CleanupExtension.class})
class CustomersTest {

    static final Factory<Map<String, Object>> CUSTOMERS = Factory.map("customer")
            .field("email", f -> f.tagged() + "@example.test");   // tf-k3x9q-t4-customer-1@example.test

    @Test
    void listsMyCustomers() {
        String id = CUSTOMERS.create(c -> api.request().body(c).post("/customers").path("id"),
                created -> api.request().delete("/customers/{id}", created));
        List<String> mine = api.request().get("/customers").jsonPath().<String>getList("email")
                .stream().filter(DataIsolation::owns).toList();
        ...
    }
}
```

- The **run tag** (`tf-k3x9q`) is fixed per JVM: generated, or
  `forge.data.isolation.run-tag` (a CI run id). With `DataIsolationExtension`
  each test adds its own part (`tf-k3x9q-t4`); it is logged with the test name.
  `DataIsolation.tag(value)`, `f.tagged()` and `scopeTag()` use the current one.
- `owns(value)` — this test's data (the run's, outside a test); `ownedByRun`
  — any test of this run; `isTestData` — any run with the prefix, for a
  sweeper job that removes what crashed runs left behind. Tags match whole:
  `t1` never owns `t17`.
- `Factory.create(persist, undo)` builds, persists and registers the undo
  with `Cleanup` as `"<factory> <result>"`; nothing is registered when
  persisting throws.
- List `DataIsolationExtension` before `CleanupExtension`, so undo actions
  still run inside the test's tag.

```yaml
forge:
  data:
    isolation:
      prefix: tf                    # letters and digits; marks test data of every run
      run-tag: ${GITHUB_RUN_ID:}    # blank: generated once per JVM
```

The tag is per JVM, not per Spring context: set `run-tag` in the environment
profile, never per test class.

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
  for metrics, default no-op. `onExhausted` fires once per drain, on the
  first cold miss; listeners receive the variant's sorted tag set.
- A variant is a type plus a **set** of tags: `{"vip", "eu"}` and
  `{"eu", "vip"}` share stock. Providers still get the caller's order.

### Preload and refill from configuration

```yaml
forge:
  data:
    pool:
      preload:
        - type: com.example.DemoAgreement   # a PreparedDataProvider must serve it
          tags: [active]
          count: 5                          # stocked when the context starts
          refill-below: 2                   # optional: top up to 5 in the background
```

- Preload runs at context start. A failing provider fails the context: a
  suite that silently ran every test on the slow path would hide exactly
  what the entry was written for.
- Refill runs on one daemon thread, at most one refill per variant at a time.
  A refill failure is logged and counted (`refillFailures`), never thrown
  into a test — the next acquire takes the cold path.
- `pool.stats()` returns hits, cold misses, prepared, exhausted and refill
  failures per variant; with `module-reporting` enabled the same numbers land
  in the run index as `module-data/pool-stats.json`, so a CI run shows which
  variants were under-stocked.

## Cleaning up what a test created

Tests on a shared environment undo what they create. Register the undo the
moment the object exists; it runs after the test, even when the test failed
halfway:

```java
@ExtendWith(CleanupExtension.class)
class OrdersTest {

    @Test
    void cancelsAnOrder() {
        String id = api.request().body(order).post("/orders").path("id");
        Cleanup.register("order " + id, () -> api.request().delete("/orders/{id}", id));
        ...
    }
}
```

- Actions run newest first — children before their parents — and every one
  runs even if an earlier one fails.
- What `@BeforeAll` registers runs after the whole class.
- Per thread: parallel tests never clean up each other's data.
- A cleanup problem never hides the test's own failure (it is attached as
  suppressed). When the test passed, data left behind fails it with every
  action that could not be undone.

## Agent notes

- `PreparedDataProvider<T>` is THE adaptation point — its `prepare(tags)`
  should drive the product API (typically through `module-state` +
  `module-flow`), never fabricate rows directly in the DB unless the project
  explicitly allows it.
- Pool objects are handed out exactly once; there is no release on purpose.
- Background refill calls the provider from another thread: a provider must
  not depend on the calling test's thread-bound state (`ScenarioContext`,
  `AuthContext`).
- Uniqueness comes from `RunUniqueValues` + `Generators`; do not invent
  ad-hoc random suffixes in tests.
- Isolation comes from `DataIsolation` tags inside values the system already
  stores (names, emails, references); do not add fields the product does not
  have just to carry a tag.
