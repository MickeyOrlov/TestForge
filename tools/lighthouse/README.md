# Front-end budgets with Lighthouse CI

Project-level workflow over Google's Apache-2.0 [Lighthouse CI](https://github.com/GoogleChrome/lighthouse-ci), pinned to `@lhci/cli` 0.16.0 in package-lock. Native Lighthouse owns measurements, aggregation and assertions. This is not a Java module and does not depend on module-a11y, module-load, module-web-playwright or module-quality.

Requires Node >=22.19.0, npm and an explicitly selected existing Chrome executable. Browser installation is a separate project/environment decision. V1 supports Linux/macOS process groups; Windows is not supported. Run from the repository root:

```bash
# A reviewed JSON array of targets; no automatic localhost/environment default:
printf '["http://127.0.0.1:8080/"]\n' > /tmp/lighthouse-urls.json
./gradlew :example-tests:lighthouseTest \
  -PlighthouseConfig=docs/examples/lighthouse/lighthouserc.json \
  -PlighthouseUrls=/tmp/lighthouse-urls.json \
  -PlighthouseChrome=/absolute/path/to/chrome
```

The task explicitly installs pinned tooling using `npm ci --ignore-scripts`, then executes native `collect`, `assert`, and `upload` with **filesystem** as the only export target. Nothing is attached to default build/test; no public report upload or GitHub status is used. Native stage stdout/stderr is discarded; raw reports remain local for inspection. Child environment is restricted to OS path/home/temp/locale variables, excluding ambient CI credentials, LHCI overrides and NODE_OPTIONS. Browser profiles/extensions and visited sites remain trusted environment inputs.

Each invocation snapshots configuration into a fresh UUID directory under `build/lighthouse/`. That directory contains native `.lighthouseci` data, exported JSON/HTML/manifest and workflow `summary.json` (tool version, URL-list SHA-256, stage statuses and outcome). Assertion failure still exports reports. Exit codes: 0 native assertions passed, 1 native assertions failed, 2 invalid configuration/execution/export/incomplete-result error. Summary adds no score or custom threshold calculations. Native warnings retain native semantics.

## Inputs and limits

The config is native **JSON** with `ci.collect.numberOfRuns` (1..10), `ci.collect.settings`, and `ci.assert.assertions` (at least one error-level assertion) **or** `ci.assert.budgetsFile` (resolved relative to the input config and snapshotted). See the example config for FCP/LCP/CLS budgets. Other native configuration, JS hooks, server commands, presets, public upload and login scripts are deliberately outside this small workflow. Use Lighthouse CI directly for those needs. Collect URLs and Chrome are supplied by the caller, never taken from config defaults. URL lists contain 1..20 explicit HTTP(S) URLs without credentials/query/fragment. Native page redirects are not origin-restricted; run against an owned test environment. Settings/config files are trusted project inputs, not untrusted uploads.

A stage has a three-minute deadline; Chrome descendants in its process group are terminated after completion or timeout. This runner is not a sandbox and does not change browser security flags. Do not pass `--no-sandbox` for routine CI. Numeric results vary with CPU, network, cache and throttling; select a controlled runner and review repeated native measurements before enforcing budgets. The included limits are examples, not universal product acceptance criteria.

Native reports can contain final URLs, DOM excerpts, screenshots and page data. Disabling full-page screenshots in the example does **not** sanitize all native artifacts. Use synthetic fixture data, review access/retention before sharing, and do not upload a whole build directory blindly. No ArtifactSink or quality reader is added here.

## Verification

```bash
node --test tools/lighthouse/run.test.mjs  # offline contract checks
./gradlew :example-tests:lighthouseAcceptance -PlighthouseChrome=/absolute/path/to/chrome
./gradlew build
```

Acceptance owns a loopback static page and verifies a generous native FCP budget passes, a zero budget fails, both runs export native reports, and directories differ. It requires no external application or public upload. A real application check of the existing faults/a11y adapters is documented in [toolbox-dogfood-petclinic.md](../../docs/research/toolbox-dogfood-petclinic.md).
