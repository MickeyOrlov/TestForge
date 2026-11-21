# Spectral workflow

Read root AGENTS.md and README.md. This is project tooling, not a production module.
- Pinned native Spectral and OWASP ruleset; never add or rewrite rules here — a project
  with its own rules extends ruleset.mjs in its adapted copy.
- Local documents only: no URL inputs, no remote `$ref` (resolver.cjs), no default-build run.
- An incomplete lint (invalid-ref, unrecognized-format, parser, no valid SARIF) writes no
  report and exits 2; never let a partial report reach module-quality.
- Keep the document path as `partialFingerprints["documentPath/v1"]`; changing its format
  changes every issue key and so every baseline.
- Refuse installed versions other than the pinned ones; the SARIF names what ran.
Verify `:example-tests:spectralUnitTest`, `:example-tests:spectralAcceptance` and
module-quality's SarifDimensionTest (its Spectral fixture comes from this workflow).
