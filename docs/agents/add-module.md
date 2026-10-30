# Playbook: add a module

Goal: a new `module-<name>` that is removable, offline-first and documented.

## 0. Decide it is a module

- It wraps a proven tool or a pattern at least two adapted projects would
  write themselves. If it only serves one project, it belongs in that project.
- It does not replace Spring, JUnit, Playwright, Appium, Allure or REST Assured
  (see "Explicitly Out Of Scope" in [docs/ROADMAP.md](../ROADMAP.md)).
- Check [BACKLOG.md](../../BACKLOG.md): if it is listed there, follow that scope.

## 1. Skeleton

```
module-<name>/
  build.gradle                       api platform(libs.spring.boot.dependencies), api project(":core")
  README.md                          what's inside, configuration, limits
  AGENTS.md                          scope, may depend on, invariants, tests
  src/main/java/io/testforge/<pkg>/
    TestForge<Name>AutoConfiguration.java
    <Name>Properties.java            record, @ConfigurationProperties(prefix = "forge.<name>")
  src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

Copy the shape of a small existing module (`module-web`, `module-flow`).

## 2. Register it

1. `settings.gradle`: add the `include`.
2. `architecture-tests/src/test/java/io/testforge/architecture/Modules.java`:
   add the root package to `ROOT_PACKAGES`, and any module edges to
   `ALLOWED_DEPENDENCIES`.
3. `example-tests/build.gradle`: `testImplementation project(":module-<name>")`.

## 3. Defaults

- Inert by default: `@ConditionalOnProperty(prefix = "forge.<name>", name = "enabled", havingValue = "true")`
  when it touches anything external (network, browser, device, broker).
- No hidden network calls, no browser/device startup in the default build.
- Optional integrations (Allure etc.) as `compileOnly`.
- Optional Spring auto-configurations you order against: `afterName`, not a
  class reference.

## 4. Tests

- Focused unit tests in `module-<name>/src/test` for logic.
- One example in `example-tests` showing the intended use. It runs offline;
  anything needing Docker/browser/live API gets a tag (`containers`,
  `browser`, `live-api`).
- No `Thread.sleep`; wait with `Waiter`.

## 5. Docs

- Root [AGENTS.md](../../AGENTS.md): one line in the Map, one adaptation step.
- [docs/ROADMAP.md](../ROADMAP.md): add it under *Experimental*.
- [CHANGELOG.md](../../CHANGELOG.md): entry under Unreleased.

## 6. Done

```bash
./gradlew :module-<name>:test :architecture-tests:test
./gradlew build
```

Then delete-test it: remove the module from `settings.gradle` locally and
confirm the remaining build is still green (restore afterwards).
