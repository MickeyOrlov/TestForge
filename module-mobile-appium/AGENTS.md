# module-mobile-appium: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-mobile-appium`; how to *use* it lives in [README.md](README.md).

**Scope:** Appium device matrix, lazy sessions, JUnit fixture, optional local node, failure artifacts.

**May depend on:** `core` only.

## Invariants (do not break)

- `enabled=true` only creates beans; sessions open lazily.
- Real devices only in explicit profiles; local node start is opt-in.
- Screen objects and provider clients belong to the adapted project.

## Tests

Own: `AppiumDeviceRegistryTest`, `AppiumCapabilitiesMapperTest`, `AppiumExtensionTest` and others. Examples: `AppiumWiringTest`, `AppiumExtensionWiringTest`.

## Before you finish

- `./gradlew :module-mobile-appium:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
