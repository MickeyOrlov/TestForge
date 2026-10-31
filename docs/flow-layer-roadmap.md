# Flow layer roadmap

`module-flow` drives a system to a deep state through deterministic steps and
fails with the path it took. What it cannot yet say: where the time went,
what the flow *could* do as opposed to what one run did, and whether the
suite ever took each branch.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Timed transitions** — every step taken with its duration; a timeout names the slowest step | `module-flow` | shipped |
| 2 | **Declared transitions** — a step states where it may go; an undeclared next state fails at once; the flow renders as a diagram | `module-flow` | planned |
| 3 | **Transition coverage** — declared transitions the whole suite never took, in the run index | `module-flow` | planned |
| 4 | **Review** — close the gaps a review of the module finds | `module-flow` | planned |
