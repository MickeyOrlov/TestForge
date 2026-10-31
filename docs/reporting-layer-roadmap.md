# Reporting layer roadmap

Every module now publishes into the run index: HTTP exchanges, SQL, Kafka
traffic, mock scopes, flow coverage, contract findings. The index has to hold
all of it for a whole run and make a failed test's evidence easy to find.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **One run per JVM** — every Spring context writes into one run, and the index holds every context's artifacts | `module-reporting` | shipped |
| 2 | **Evidence per failed test** — `summary.md` groups each failed test's diagnostics from every module (HTTP, SQL, Kafka) under the test | `module-reporting` | planned |
| 3 | **Review** — close the gaps a review of the module finds | `module-reporting` | planned |
