# Release Checklist

This checklist keeps publication boring and repeatable.

## Day 0: before publishing

- README presents TestForge as a template and automation accelerator.
- `LICENSE`, `NOTICE`, `CONTRIBUTING.md`, `AGENTS.md`, `CHANGELOG.md`,
  `BACKLOG.md`, and `docs/ROADMAP.md` are present.
- Architecture diagrams are visible in README.
- `./gradlew --no-daemon clean build --warning-mode all` is green.
- Docker runner image builds successfully.
- GitHub Actions syntax is committed and ready to run after push.
- Repository scan is clean for task markers, secrets, private-domain terms, and
  scratch notes.

```bash
rg -n --hidden --glob '!.git/**' --glob '!**/build/**' 'secret|password|token' .
```

## Day 1: first GitHub release

Recommended first public release: `v0.1.0`.

Release highlights:

- Core context and waits.
- `Waiter` / `DbWaiter`.
- DB schema checks.
- Kafka probe.
- Scoped WireMock mocks.
- JSON contract validation.
- Playwright fixtures and artifacts.
- Appium device matrix and artifacts.

Suggested GitHub topics:

```text
java
spring-boot
qa
sdet
automation
playwright
appium
rest-assured
wiremock
kafka
testing
junit5
```

If GitHub CLI is available after the repository is created:

```bash
gh repo edit --add-topic java --add-topic spring-boot --add-topic qa \
  --add-topic sdet --add-topic automation --add-topic playwright \
  --add-topic appium --add-topic rest-assured --add-topic wiremock \
  --add-topic kafka --add-topic testing --add-topic junit5
```

Before creating `v0.1.0`, check local and remote tags. A public first release
should not conflict with existing published tags.
