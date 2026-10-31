# Flow layer roadmap

`module-flow` drives a system to a deep state through deterministic steps and
fails with the path it took. What it cannot yet say: where the time went,
what the flow *could* do as opposed to what one run did, and whether the
suite ever took each branch.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Timed transitions** — every step taken with its duration; a timeout names the slowest step | `module-flow` | shipped |
| 2 | **Declared transitions** — a step states where it may go; an undeclared next state fails at once; the flow renders as a diagram | `module-flow` | shipped |
| 3 | **Transition coverage** — declared transitions the whole suite never took, in the run index | `module-flow` | shipped |
| 4 | **Review** — close the gaps a review of the module finds | `module-flow` | shipped |

All four steps shipped (PRs #67–#70). The review found a flow that reached
its target and still failed: a step that put `null` into the `FlowContext`
broke the result's snapshot with a `NullPointerException`. A null now removes
the key. It also stopped successful runs from writing a path file each — the
run index flooded on a real suite; failures still always write theirs. Known
limit, now documented: the timeout is checked between steps, so a blocking
step must bound its own wait.
