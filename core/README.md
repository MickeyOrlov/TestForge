# core

The deliberately thin foundation. A class earns its place here only when two
or more modules need it.

## What's inside

- **`ScenarioContext` / `ContextKey<T>`** — typed thread-local storage for
  values produced during a scenario (ids, responses, expected values).
  Parallel-safe as long as one scenario stays on one thread. Call
  `ScenarioContext.clear()` in an after-hook.
- **`Waiter`** — the single entry point for waiting on asynchronous effects.
  Polls with a deadline instead of sleeping. Defaults come from `forge.wait.*`.

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
