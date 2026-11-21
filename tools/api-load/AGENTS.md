# Project-level API → load composition

This is optional tooling in the adapted project, not a production module or an OpenAPI generator.

- Generator: pinned `@grafana/openapi-to-k6`, installed from package-lock with lifecycle scripts disabled.
- YAML parsing belongs to the existing yaml library. Do not implement schemas, payload synthesis,
  request scheduling or a workload DSL here. Native scenario.ts owns the business flow and k6 options.
- Exchange format: existing producer JSON reports and a bundled standard OpenAPI snapshot.
  Never import producer Java classes into module-load or add production dependency edges.
- Run directories are fresh. Research, generator and load consume the same immutable snapshot.
  Input/report/bundle hashes detect accidental edits and stale reuse; they are not cryptographic signatures.
- Selection declares research prerequisites, not a sandbox around trusted native scripts.
- Never replay observation payloads, resolved URLs or credential values into generated code.
- The vendored URL helper is upstream code; retain its checksum, license and attribution.
- npm/Node/native k6 execute only via explicit workflow tasks, never the default Gradle build.

Verify `npm test --prefix tools/api-load`, focused Java tests and `./gradlew build`.
`acceptance.py --k6 ...` is an opt-in check against its own loopback server: happy path,
standalone native bundle and a broken contract that stops before load.
