# Reporting layer roadmap

Every module now publishes into the run index: HTTP exchanges, SQL, Kafka
traffic, mock scopes, flow coverage, contract findings. The index has to hold
all of it for a whole run and make a failed test's evidence easy to find.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **One run per JVM** — every Spring context writes into one run, and the index holds every context's artifacts | `module-reporting` | shipped |
| 2 | **Evidence per failed test** — `summary.md` groups each failed test's diagnostics from every module (HTTP, SQL, Kafka) under the test | `module-reporting` | shipped |
| 3 | **Review** — close the gaps a review of the module finds | `module-reporting` | shipped |

All three steps shipped (PRs #77–#79). The serious gap was step 1's: a
multi-context suite split its index or lost most of it. The review of the
rest — sink, layout, summary, resource monitor — found nothing broken; one
limit is now documented: each Spring context runs its own resource monitor,
so a suite with several contexts writes several overlapping
`resource-usage*.txt` samples of the same JVM.
