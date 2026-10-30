# Playbook: review a module

Use for a maturity check of a whole module or a review of a change to it.
Report findings as *blocking* or *advisory*, each with file and line.

## Structure (mostly enforced by `architecture-tests`)

- [ ] Depends only on `core` and the modules listed for it in `Modules.java`.
- [ ] `TestForge*AutoConfiguration` listed in `AutoConfiguration.imports`.
- [ ] `*Properties` is a record under `forge.*` with defaults in the compact
      constructor.
- [ ] `README.md` and `AGENTS.md` exist and match the code.

## Behaviour

- [ ] Inert by default when it reaches anything external.
- [ ] Fails closed: disabled or misconfigured checks say so, never pass green.
- [ ] No swallowed exceptions except documented best-effort paths
      (prewarm, reporting, failure artifacts), and those log a warning.
- [ ] Secrets and payload values never reach logs, artifacts or snapshots
      unredacted.
- [ ] Waiting uses `Waiter`; no sleeps, no busy loops.
- [ ] Deterministic output (ordering, file names) where it is diffed or
      committed.

## Tests

- [ ] Own unit tests for non-trivial logic (the module `AGENTS.md` lists gaps).
- [ ] An offline example in `example-tests`; environment-backed tests are
      tagged and excluded from the default build.
- [ ] Every new branch of behaviour has a test that fails without it.

## Docs

- [ ] Status in [docs/ROADMAP.md](../ROADMAP.md) (stable/beta/experimental)
      still true.
- [ ] Sharp edges a user can hit are in root `AGENTS.md`.
