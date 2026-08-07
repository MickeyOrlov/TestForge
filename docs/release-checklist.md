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

## Library release

The next release after the existing `v1.1.0` tag is `v1.2.0`. Keep the default
development version at `1.2.0-SNAPSHOT`, then override it for the release:

```bash
./gradlew publishTestForgeLibraries -PtestforgeVersion=1.2.0
./gradlew -p smoke-tests/library-consumer test -PtestforgeVersion=1.2.0
```

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

Before creating `v1.2.0`, check local and remote tags and verify the target
Maven repository coordinates. The public GitHub Packages coordinates are
`io.github.mickeyorlov.testforge:<module>:<version>`.

Run the manual `Publish Maven packages` workflow only after the release commit
has passed local verification. For a local deployment, use a classic PAT with
`write:packages`; consumers need `read:packages`. Verify the deployed modules
through `smoke-tests/library-consumer` using the remote repository URL from
`docs/library-consumption.md`.

Maven Central additionally requires a verified namespace, signed artifacts,
and repository-specific release configuration.
