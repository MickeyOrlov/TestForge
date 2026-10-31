# Core roadmap

`core` holds what two or more modules share: `ScenarioContext`, `Waiter`,
`StateSnapshot`/`StateDiff`, `JsonPath` and the `ArtifactSink` seam. `Waiter`
is the one most tests touch, and its timeouts said only "did not complete
within 30 seconds": not what the last poll saw, and — because exceptions were
ignored while polling — not that every poll had failed with the same error.
It also could not stop early: a job that went `Failed` was waited on for the
full timeout.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Timeouts that say what was seen** — last value, last poll error, number of polls | `core` | shipped |
| 2 | **Stop early, per-wait timeouts** — fail at once on a terminal state; a different deadline for one wait | `core` | shipped |
| 3 | **Review** — close the gaps a review of the module finds | `core` | planned |

## Step 1 — timeouts that say what was seen

`Waiter.await` and `awaitTrue` now throw `WaitTimeoutException` (a
`ConditionTimeoutException`, so existing catches keep working):

```
Waited PT30S for job j-7 becomes Completed (61 polls); last value: Job[id=j-7, status=Failed]
Waited PT30S for order o-1 is visible (61 polls); no poll produced a value; last error: IllegalStateException: GET /orders/o-1 -> 503
```

Exceptions are still "not ready yet" while polling, but the last one is kept
and reported. Values are cut at 500 characters, errors to their first line.
`lastValue()`, `lastError()` and `polls()` are on the exception for tests that
want them.

## Step 2 — stop early, per-wait timeouts

`await(description, supplier, until, failWhen)` checks each polled value
against a terminal-state predicate and throws `WaitAbortedException` — an
`AssertionError`, a test failure — the moment it matches, naming the value
and the number of polls. A job that went `Failed` fails the test now, not
after the 30-second default.

`withTimeout(Duration)` and `withPollInterval(Duration)` return a waiter for
the one wait that needs another deadline (a slow report) or cadence. The
interval is capped at half the deadline: Awaitility rejects one as long as
the timeout, which also made a short `forge.wait.timeout` with the default
interval fail with Awaitility's own error.
