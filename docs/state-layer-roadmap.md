# State layer roadmap

`module-state` turns a tag like `approved` into a domain object driven there
by a flow. Building on the flow layer: each recipe should be visible on its
own, a typo in a tag should fail with the choices, and every recipe should be
checkable before a single test needs it.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Recipes under their own name** — coverage, artifacts and failures per recipe; what a state cost | `module-state` | shipped |
| 2 | **Supported targets and recipe verification** — an unknown tag fails with the supported ones; every recipe's flow verified for every target in one test | `module-state` | shipped |
| 3 | **Review** — close the gaps a review of the module finds | `module-state` | planned |
