# module-mobile-appium

Appium driver factory for mobile tests: managed driver lifecycle via
auto-configuration (`forge.appium.*`).

**Status: skeleton** — wiring exists, screen-object conventions and example
tests arrive with roadmap stage 7.

## Agent notes

- Keep the module deletable: no other module may depend on it.
- Device farm credentials are environment configuration, never code.
- When developing stage 7: screen-object conventions + one example test in
  example-tests in the same commit.
