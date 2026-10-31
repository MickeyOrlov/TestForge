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

## Supported targets, checked before any test needs them

```java
@Override
public Set<String> targets() {
    return Set.of("submitted", "approved");
}
```

- `@Prepared(tags = "aproved")` then fails at once with `Recipe
  state:DemoTicket cannot prepare 'aproved'; supported targets: [approved,
  submitted]`, instead of whatever `targetState` makes of a typo.
- `StateRecipes` holds every recipe bean; one test checks them all without
  creating anything:

  ```java
  @Test
  void everyRecipeCanReachEveryTarget(@Autowired StateRecipes recipes) {
      recipes.assertAllValid();
  }
  ```

  For each declared target it builds the request, maps it to states, builds
  the steps and runs `FlowRunner.verify`: a dead end, a target no declared
  transition reaches, or a target the recipe cannot map are reported. Only
  steps declared with `FlowStep.of(state, action, next...)` can be judged —
  and since steps are built per request, each should declare what it can
  return *for that request* (the step at `SUBMITTED` declares the requested
  target, not every outcome).

## What a state costs, and which branches were never prepared

Every recipe's flow runs under the recipe's `name()` — `state:<Type>` by
default — so `flow-coverage.md` lists each recipe's untaken branches on its
own, and failure messages say `State recipe state:DemoTicket failed for tags
[...]`. `prepareDetailed(...).duration()` and `flowResult().transitions()`
say what the state cost, step by step.

## Agent notes

- A state recipe should drive the product through public APIs or accepted test
  support APIs. Do not write rows directly unless the adapted project has
  explicitly chosen DB setup as its contract.
- Keep flow steps idempotent and small; reporting, metrics and Allure belong
  in `FlowStepDecorator`.
- `StatePreparedDataProvider` is intentionally one bean per domain type. That
  keeps `PreparedDataPool` exact and prevents two recipes from racing to own
  the same fixture class.
