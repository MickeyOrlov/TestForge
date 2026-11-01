# module-security: working on this module

Read root AGENTS.md and README.md first.

Scope: security findings, explicit coverage and gates; passive API response
checks; subsequent opt-in probes and delegated scans.
May depend on core, module-http, module-auth and module-api-explorer. No dependency on module-quality/reporting.

Invariants:
- Inert by default. Passive checks add no traffic and never replace a response.
- Never retain observed values in findings, coverage, logs, errors or SARIF.
- Missing/incomplete coverage never passes the gate or resolves old findings.
- Accepted IDs require reasons; baseline promotion is explicit.
- Additional traffic requires explicit configured test targets and bounded work.

Tests: SecurityPassiveTest, SecurityAuthTest, SecurityPassiveExampleTest and
SecurityAuthExampleTest. Before finishing:
`./gradlew :module-security:test :architecture-tests:test`, then `./gradlew build`.
Update README/examples/CHANGELOG with behavior changes. Keep the module removable.
