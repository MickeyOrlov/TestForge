# Toolbox validation against Spring Petclinic

Date: 2026-11-03. This check answers whether the existing Toxiproxy and axe handles compose with a real application without source changes. It does not certify product quality or reliability. A reproducible standalone harness is in [tools/toolbox-dogfood](../../tools/toolbox-dogfood).

## Inputs

- Application: [spring-projects/spring-petclinic](https://github.com/spring-projects/spring-petclinic/tree/500158f732419217507c7656904b8e6aa1bcc0d6), commit `500158f732419217507c7656904b8e6aa1bcc0d6`.
- The source tree was unchanged. Maven packaged the application with `-DskipTests` using JDK 21; its own tests were not run.
- Built JAR SHA-256: `d0675e7be278286d1f3b7a229badf8a58eb077b1274db271600b5389e669f04a` (a local build identity, not a reproducible-build guarantee).
- TestForge main `2ffaf3cf7db9e6fc49c85d3e1115821127c9e8e1`, published `module-faults` and `module-a11y` 1.3.0-SNAPSHOT, PostgreSQL 17.4-alpine, native axe 4.11.4.
- Product configuration selects the PostgreSQL profile and scope endpoint **before startup**. JDBC connect/socket timeouts, Hikari pool/timeouts and loopback HTTP binding are explicit fixture settings.

## Observed results

| Check | Result |
|---|---|
| Healthy `/owners?lastName=` | HTTP 200 |
| Downstream database latency, native Toxiproxy 2000 ms | HTTP 500 after 1628 ms (1567 ms on the reproduction run); bounded by the fixture's database timeouts |
| Remove toxic and await recovery | HTTP 200 |
| Native axe on `/owners/find` | 6 violations; 0 incomplete/manual-review findings |
| Rule IDs | `color-contrast`, `html-has-lang`, `label`, `link-name` |
| Explicit baseline adoption | Existing findings pass the adoption check |
| Add an image missing alt text to the test page | New violation blocks the check |

These six violations are native findings from this page/configuration, not six broken business scenarios or an accessibility score. The additional missing-alt image is deliberately injected by the harness and is not attributed to upstream source. The fault outcome shows failure detection and restoration under one bounded latency case; an HTTP 500 is not graceful degradation or proof of resilience under every network failure.

## Architectural conclusion

Both handles fit the toolbox model. Toxiproxy owns network faults and axe owns WCAG rules; TestForge supplies scopes, lifecycle, adoption and evidence. No application source edit or hard dependency between adapters was needed. The adaptation work belongs in the project fixture: route the dependency before opening connections, own cleanup and choose page readiness/templates. Nothing in module-quality was changed. Evidence does not justify adding another abstraction or expanding these adapters before a concrete project need appears.

Raw application logs are retained locally only. The harness writes native sanitized accessibility outputs and a small status/count summary; timing and findings can differ across environments.
