# Adaptation checklist

One pass from cloned template to a project-ready framework. Works for humans
and agents alike; the detailed playbook lives in [AGENTS.md](../AGENTS.md).

## 1. Identity

- [ ] Rename group `io.testforge` and packages to the target namespace
- [ ] `rootProject.name` in settings.gradle
- [ ] Review LICENSE/NOTICE ownership

## 2. Environments

- [ ] Copy `application-staging.example.yml` → `application-<env>.yml` per
      environment; fill endpoints, keep secrets in env vars / secret manager
- [ ] Verify `-DtestEnv=<env>` selects the profile (forwarded by the root
      build.gradle into forked test JVMs)
- [ ] CI: one job per environment (see `.gitlab-ci.yml` / `.github/workflows`)

## 3. Modules (delete what you don't need first)

- [ ] Remove unused module directories + their `settings.gradle` lines;
      build must stay green after deletion
- [ ] **module-mock**: find the request field that ties a downstream call to
      one scenario; point `forge.mock.scope-json-path` at it. Without it
      parallel runs WILL fight over shared stubs
- [ ] **module-db**: add entities + repositories for asserted tables; one
      `SchemaValidator` test per entity in a scheduled CI job. If the product
      publishes client/DTO artifacts — depend on them, never duplicate
- [ ] **module-contract**: encode external payloads as `MessageContract`
      (quick field checks) or `SchemaContract` resources (full JSON Schema)
- [ ] **module-kafka**: list topics, enable only in broker-reachable profiles
- [ ] **module-data**: implement `PreparedDataProvider<T>` per expensive
      domain state (drive the product API — typically a module-flow run);
      preload hot variants in a suite hook
- [ ] **module-web**: 2–4 heaviest pages into `forge.prewarm.urls` (CI profile)

## 4. Tests

- [ ] Wire `ScenarioContextExtension` on test base classes that use
      `ScenarioContext` (worker threads are reused — leftovers leak)
- [ ] Unique data per run only (`RunUniqueValues` + `Generators`), no shared
      mutable fixtures
- [ ] Side-effect assertions via `StateSnapshot`/`StateDiff` where a plain
      "row exists" check would hide duplicates or neighbour damage

## 5. Hygiene before the first push

- [ ] `./gradlew build --rerun-tasks` green
- [ ] No secrets in git (grep for passwords/tokens/keys)
- [ ] No previous-employer domain terms in `src/`
- [ ] `example-tests/README.md` table lists every example test
