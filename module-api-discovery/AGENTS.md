# module-api-discovery: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-api-discovery`; how to *use* it lives in [README.md](README.md).

**Scope:** Reads OpenAPI documents into an `EndpointCatalog` and schema shape snapshots; diffs them against baselines. No HTTP calls to the API.

**May depend on:** `core` only. It owns the `forge.api-discovery.specs` registry the other `module-api-*` reuse.

## Invariants (do not break)

- Offline: specs come from local files in the default build; URL specs only in
  environment profiles.
- Snapshots hold schema shape, never example values.
- Catalog and snapshot output is deterministic (stable ordering) so diffs are
  reviewable.

## Tests

Own: `ApiDiscoveryRunnerTest`, `OpenApiShapeNormalizerTest`. Example: `ApiDiscoveryExampleTest`.

## Before you finish

- `./gradlew :module-api-discovery:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
