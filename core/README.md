# core

The deliberately thin foundation. A class earns its place here only when two
or more modules need it.

## What's inside

- **`ScenarioContext` / `ContextKey<T>`** — typed thread-local storage for
  values produced during a scenario (ids, responses, expected values).
  Parallel-safe as long as one scenario stays on one thread. Register
  `@ExtendWith(ScenarioContextExtension.class)` (JUnit 5) for automatic
  cleanup between tests, or call `ScenarioContext.clear()` in an after-hook.
- **`Waiter`** — the single entry point for waiting on asynchronous effects.
  Polls with a deadline instead of sleeping. Defaults come from `forge.wait.*`.
  A timeout throws `WaitTimeoutException` (a `ConditionTimeoutException`)
  naming the last value seen and the last error a poll threw:

  ```
  Waited PT30S for job j-7 becomes Completed (61 polls); last value: Job[id=j-7, status=Failed]
  ```

  A state the value will not leave can end the wait at once, and one wait can
  have its own deadline:

  ```java
  waiter.withTimeout(Duration.ofMinutes(3)).await("report r-1 is ready",
          () -> reports.get("r-1"),
          report -> report.status() == READY,
          report -> report.status() == FAILED);   // WaitAbortedException now, not a timeout in 3 minutes
  ```

  Polls are never spaced wider than half the deadline.
- **`io.testforge.core.messaging`** — what every broker adapter shares:
  `BrokerMessage` (channel, key, value, headers, position), a bounded
  `MessageBuffer` with watermarks, `MessageCriteria` (key, value fragments,
  headers, JSON paths) and `MessageProbe` (await, exactly one, none within a
  window; a timeout names the closest messages and failed criteria, never a
  payload), and `MessageTrafficReport`, the failed-test traffic report both
  diagnostics extensions write. Tests use the adapters (`KafkaProbe`,
  `RabbitProbe`); this is for writing a new one. See [docs/messaging-layer-roadmap.md](../docs/messaging-layer-roadmap.md).

## Configuration

```yaml
forge:
  wait:
    timeout: 30s        # default
    poll-interval: 500ms # default
```

## Usage

```java
ContextKey<String> REQUEST_ID = ContextKey.of("REQUEST_ID", String.class);
ScenarioContext.put(REQUEST_ID, response.requestId());
// ... later, possibly in another step ...
String requestId = ScenarioContext.get(REQUEST_ID); // throws with a readable message if absent
ScenarioContext.remove(REQUEST_ID);                  // drops one value, keeps the rest

waiter.await("job becomes Completed",
        () -> jobClient.job(id),
        job -> "Completed".equals(job.status()));
```

## StateSnapshot / StateDiff

Side-effect assertions stronger than "the row exists": snapshot a collection
before the action, diff after, and assert exactly what appeared, disappeared
or changed. Catches duplicate inserts from retries, accidental updates of
neighbouring rows and cascade deletes — the bug classes that "find the row"
checks silently miss.

```java
var before = StateSnapshot.of(repository.findAll(), TaskRecord::getId);

// ... the action under test ...

StateDiff<TaskRecord> diff = before.diff(repository.findAll(),
        (a, b) -> Objects.equals(a.getStatus(), b.getStatus()));

assertThat(diff.added()).hasSize(1);
assertThat(diff.removed()).isEmpty();
assertThat(diff.changed()).isEmpty();
```

The second argument is a sameness predicate for entities without a
content-based `equals`; omit it to compare with `Object#equals`.

## Agent notes

- Core earns a class only when two or more modules need it — otherwise the
  class belongs in a module.
- `ScenarioContext` has two carriers: thread-local by default,
  `runScoped(Runnable)` for an isolated block (nested preparation must not
  pollute the calling test's context).
- Never add sleeps anywhere in this repo — `Waiter` polls on the calling
  thread; conditions must stay quick queries.
