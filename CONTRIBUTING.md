# Contributing to TestForge

TestForge is a template repository. Contributions should keep it easy to clone,
rename, trim down, and adapt inside another organization.

## Development Setup

```bash
./gradlew build
```

The default build must stay offline-first. Examples that need Docker, browsers,
real devices, or external environments belong in explicit Gradle tasks, profiles
or CI jobs.

## Contribution Rules

- Keep `core` thin. Add shared code there only when at least two modules need it.
- Keep modules deletable. Removing an unused module and its `settings.gradle`
  entry should not break unrelated modules.
- Prefer proven tools over custom infrastructure: Spring Boot, JUnit,
  Playwright, Appium, WireMock, Testcontainers, REST Assured and Allure are
  integration points, not things TestForge replaces.
- Do not introduce sleeps. Use `Waiter`, `DbWaiter`, or a focused polling
  condition.
- Do not commit secrets, real credentials, customer data, or organization
  specific names.
- Keep Allure and other reporting integrations optional unless the module's
  purpose explicitly requires them.
- Keep generated artifacts out of the repository.

## Definition of Done

- `./gradlew build --warning-mode all` is green.
- New behavior has an example or focused test in `example-tests` or the owning
  module.
- README, module README, roadmap, and changelog are updated when public behavior
  changes.
- The change keeps the default build offline-first and CI-safe.
- A repository hygiene scan finds no private-domain terms, secrets, or leftover
  scratch notes.

## Pull Requests

Use small pull requests with a clear description:

- what changed;
- why it belongs in the template rather than an adapted project;
- how it was verified;
- any compatibility or migration notes.

For larger features, start from `BACKLOG.md` or open an issue first so the scope
stays aligned with the project philosophy.
