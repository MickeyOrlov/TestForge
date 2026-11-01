# module-flow

Small state-machine runner for preparing complex test states.

## What's inside

- **`FlowStep<S>`** — one transition handler, usually backed by an API call or
  database/action helper.
- **`FlowRunner<S>`** — runs from a start state until a target state, recording
  the path and enforcing guardrails.
- **`FlowContext`** — per-run scratchpad for values produced by steps.
- **`FlowStepDecorator`** — around-hook for logging, timing, reporting; steps
  stay unaware. **`LoggingFlowStepDecorator`** is the default when you call
  `create(steps)` without a decorator list.
- **`AllureFlowStepDecorator`** — reports each transition as an Allure step
  (preparation paths read as collapsible timelines). Optional: compiled
  against `allure-java-commons` (`compileOnly`), add it to your test module's
  runtime classpath to use this.

Use this when a test needs the system to reach a deep state but the path can
branch depending on service responses (role, amount threshold, API outcome). The
runner logs and returns the path, and fails loudly if the flow loops or exceeds
limits.

For reusable domain setup, put the flow behind `module-state`: a
`StateRecipe<T, S>` maps tags such as `approved` to a target state and
`StatePreparedDataProvider` feeds the resulting object into `@Prepared`.

## Configuration

```yaml
forge:
  flow:
    timeout: 60s
    max-transitions: 100
    max-visits-per-state: 5
    publish-successful-paths: false   # default: only failed runs write flow-path-<name>.txt
```

The timeout is checked between steps; a step that blocks is not interrupted,
so a step that waits must bound its own wait (`Waiter`, `DbWaiter`).
`FlowContext.put(key, null)` removes the key.

## Usage

```java
FlowRunner<OrderState> runner = flowRunnerFactory.create(steps);
FlowResult<OrderState> result = runner.run(OrderState.START, OrderState.READY);

// custom decorators (empty list = no logging wrapper)
FlowRunner<OrderState> raw = flowRunnerFactory.create(steps, List.of());
FlowRunner<OrderState> timed = flowRunnerFactory.create(
        steps, List.of(new LoggingFlowStepDecorator<>(), myMetricsDecorator));
```

`result.transitions()` lists every step taken with how long it ran
(`FlowTransition(from, to, duration)`), `result.duration()` the total. A flow
that timed out names its slowest step (`slowest step: AUTHORIZE -> READY
(41000 ms)`), a failed step says how long it ran before failing, and the
`flow-path-<name>.txt` artifact lists the timed steps.

### Declared transitions: verify and draw a flow before it runs

```java
FlowStep.of(SUBMITTED,
        ctx -> ctx.get("amount", Integer.class) > 1000 ? MANAGER_REVIEW : OPERATOR_REVIEW,
        MANAGER_REVIEW, OPERATOR_REVIEW)   // where this step may go
```

- A step that returns a state it does not declare fails at once:
  `Step at SUBMITTED returned APPROVED, which it does not declare`.
- `runner.verify(start, target)` / `assertValid(...)` find, without calling
  the system, a state some step can reach that has no step (a dead end) and
  a target no declared path reaches.
- `runner.describe()` renders a Mermaid `stateDiagram-v2` from the
  declarations — for a README or a review.
- Declaring is optional (`FlowStep.declaredNext()` defaults to empty): steps
  that declare nothing are not checked and draw as `--> ?`. The method is
  `declaredNext`, so a `record Step(S state, S next)` keeps compiling.

### Which branches the suite never took

Every runner records the declared transitions it took into the shared
`FlowCoverage` (per flow name, across runners and tests). When the context
stops, `module-flow/flow-coverage.md` lands in the run index:

```
## approval
- runs: 14
- declared transitions taken: 2 of 4
- never taken by any test:
  - `MANAGER_REVIEW -> APPROVED`
  - `SUBMITTED -> MANAGER_REVIEW`
```

Report-only (`healthy: true`, `uncovered` as metadata): a green suite that
never exercised the manager review is a gap to read about. Name runners
(`create("approval", steps)`) so coverage adds up; only declared transitions
count. `flowRunnerFactory.coverage()` gives the same in a test.
`flow-coverage.json` beside it carries the same entries for
`module-quality`'s `flow-coverage` dimension (WARN while a declared
transition is untaken).

Branching example: put `role` or `amount` into `FlowContext` in a setup hook,
then let the step at `SUBMITTED` return `OPERATOR_REVIEW` or `MANAGER_REVIEW`
— see `FlowRunnerTest.roleBasedApprovalPath` in example-tests.

## Agent notes

- Keep steps small and idempotent; branching lives inside a step (return a
  different next state), not in the runner.
- If the same flow prepares fixtures for several tests, wrap it in
  `module-state` instead of duplicating `FlowRunner.run(...)` calls.
- Cross-cutting concerns (logging, Allure, metrics) go through
  `FlowStepDecorator` — never edit steps for reporting.
- `AllureFlowStepDecorator` needs allure-java-commons on the runtime
  classpath; the module itself must keep Allure optional (compileOnly).
