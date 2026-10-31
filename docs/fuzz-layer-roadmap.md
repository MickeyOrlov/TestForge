# API fuzz roadmap

`module-api-fuzz` runs Schemathesis against an environment and ingests its
NDJSON stream. What came back was a flat list: every generated case that
broke a check was its own finding, so fifty cases tripping
`not_a_server_error` on one operation read as fifty problems, and nothing said
how to rerun one. And `fail-on-findings` was all or nothing: one known,
accepted finding kept the gate off for everything else.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Findings a person can read** — grouped by check and operation, `report.md` per run with the command that reproduces each | `module-api-fuzz` | shipped |
| 2 | **Accepted findings** — known findings listed once; the gate fails only on new ones, and says which accepted ones no longer occur | `module-api-fuzz` | shipped |
| 3 | **Review** — close the gaps a review of the module finds | `module-api-fuzz` | planned |

## Step 1 — findings a person can read

Findings carry the spec they came from and group by `<check> <operation>`
(`ApiFuzzFindingGroup`, `ApiFuzzReport.findingGroups()`), with the number of
cases, the phases and methods that found them and the first message. Each run
writes `<output-dir>/report.md`: per spec, the outcome, scenario counts and one
entry per group, ending in the command that replays it — the run's own
arguments, report flags dropped, the URL redacted, narrowed with
`--include-name '<operation>'`. Only the first line of a message is kept (a
server's traceback stays in the NDJSON); headers and bodies are never written.

## Step 2 — accepted findings

`forge.api-fuzz.accepted-findings` takes the group headings `report.md`
prints — `not_a_server_error GET /items/{id}`, optionally `<spec>:`-scoped,
with ` # why` for the reader. `fail-on-findings` fails on groups no entry
covers; the report marks accepted groups and lists entries a completed run no
longer matched, so the list shrinks as things get fixed instead of rotting.
The outcome stays `FINDINGS` — acceptance changes the verdict, not the facts —
and a run that did not complete reports nothing stale, since it proved
nothing.
