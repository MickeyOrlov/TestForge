# module-web-playwright

Playwright bootstrap for UI tests: managed `Playwright`/`Browser` lifecycle
via auto-configuration (`forge.playwright.*`).

**Status: skeleton** — wiring exists, page-object conventions and example
tests arrive with roadmap stage 7.

## Agent notes

- Keep the module deletable: no other module may depend on it.
- Browser binaries belong in the CI runner image (see Dockerfile pattern for
  module-web prewarm), not in per-run downloads.
- When developing stage 7: locator conventions first (data-testid/getByRole),
  one example test in example-tests in the same commit.
