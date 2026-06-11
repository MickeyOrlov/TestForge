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

Use this when a test needs the system to reach a deep state but the path can
branch depending on service responses (role, amount threshold, API outcome). The
runner logs and returns the path, and fails loudly if the flow loops or exceeds
limits.

## Configuration

```yaml
forge:
  flow:
    timeout: 60s
    max-transitions: 100
    max-visits-per-state: 5
```

## Usage

```java
FlowRunner<OrderState> runner = flowRunnerFactory.create(steps);
FlowResult<OrderState> result = runner.run(OrderState.START, OrderState.READY);

// custom decorators (empty list = no logging wrapper)
FlowRunner<OrderState> raw = flowRunnerFactory.create(steps, List.of());
FlowRunner<OrderState> timed = flowRunnerFactory.create(
        steps, List.of(new LoggingFlowStepDecorator<>(), myMetricsDecorator));
```

Branching example: put `role` or `amount` into `FlowContext` in a setup hook,
then let the step at `SUBMITTED` return `OPERATOR_REVIEW` or `MANAGER_REVIEW`
— see `FlowRunnerTest.roleBasedApprovalPath` in example-tests.
