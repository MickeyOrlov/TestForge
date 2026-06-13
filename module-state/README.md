# module-state

Reusable state recipes on top of `module-flow` and `@Prepared`.

## What's inside

- **`StateRecipe<T, S>`** — product-specific recipe that knows how to move a
  domain object through flow states until the requested target is reached.
- **`StateRecipeExecutor`** — creates a `FlowRunner`, executes the recipe, and
  returns both the materialized object and the flow path.
- **`StatePreparedDataProvider`** — adapter to `PreparedDataProvider<T>` so
  tests can inject objects with `@Prepared(tags = "approved")`.
- **`StateRequest`** — immutable tags view. A single tag (`approved`) is treated
  as the target state; with multiple tags use the prefix (`state:approved`) and
  keep the rest for tenant/flavour/feature flags.

Use this when a test should start from a meaningful business state instead of
clicking through or copying setup calls in every test body.

## Configuration

```yaml
forge:
  state:
    target-tag-prefix: "state:"
```

## Usage

```java
@Bean
StateRecipe<DemoTicket, DemoTicketState> demoTicketRecipe(RunUniqueValues uniqueValues) {
    return new DemoTicketRecipe(uniqueValues);
}

@Bean
PreparedDataProvider<DemoTicket> demoTicketProvider(
        StateRecipe<DemoTicket, DemoTicketState> recipe,
        StateRecipeExecutor executor) {
    return StatePreparedDataProvider.of(recipe, executor);
}

@Test
void opensReadyTicket(@Prepared(tags = "approved") DemoTicket ticket) {
    // ticket was created by the recipe and moved through the flow path
}
```

For variants with extra tags:

```java
@Prepared(tags = {"state:approved", "tenant:demo", "large"})
DemoTicket ticket
```

## Agent notes

- A state recipe should drive the product through public APIs or accepted test
  support APIs. Do not write rows directly unless the adapted project has
  explicitly chosen DB setup as its contract.
- Keep flow steps idempotent and small; reporting, metrics and Allure belong
  in `FlowStepDecorator`.
- `StatePreparedDataProvider` is intentionally one bean per domain type. That
  keeps `PreparedDataPool` exact and prevents two recipes from racing to own
  the same fixture class.
