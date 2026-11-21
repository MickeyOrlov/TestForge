# Request for Gemini Deep Research: a market review of quality assessment tools

> The text below is self-contained, with no references to this repository.
> Copy it whole, starting from "Research the market". It was sent in Russian;
> this is a translation, so the language rule at the end says what the
> original asked for.

---

Research the market of open-source (and, for comparison, leading commercial)
tools that **assess the quality of a software build or release from the
results of automated tests and related checks** and decide "let it through /
stop it" (a quality gate).

I need to understand which approaches and ideas the market considers strong,
to design my own quality assessment module for a Java test automation
framework. I do not plan to copy code — I am interested in ideas, data
models, formulas and algorithms.

## Classes of solutions to consider

The examples are pointers, not a limit. Add strong projects that are not on
the list and explain why they matter.

1. **Quality gates and "new code only"** — SonarQube / SonarCloud (Quality
   Gate, New Code, "Clean as You Code"), Codecov, Coveralls (patch coverage,
   flags, components), OpenSSF Scorecard, Lighthouse CI (budgets, assertions).
2. **Test analytics, flaky tests, failure triage** — ReportPortal, Allure
   Report / Allure TestOps, Gradle Develocity, BuildPulse, Trunk Flaky Tests,
   Buildkite Test Engine, Datadog CI Visibility; research on flakiness
   (DeFlaker, iDFlakies, FlakeFlagger and newer).
3. **SLO-based assessment and statistical comparison of runs** — Keptn
   quality gates, Kayenta (canary analysis), Argo Rollouts Analysis, Iter8,
   thresholds in k6 and Gatling.
4. **The effectiveness of the tests themselves** — mutation testing (PIT,
   Stryker), code coverage and change coverage (JaCoCo, diff-cover).
5. **Findings management and risk acceptance** — DefectDojo, OWASP
   Dependency-Track, the SARIF format (baselineState, fingerprints).
6. **Scorecards and delivery metrics** — DORA, CHAOSS, Backstage Tech
   Insights / Scorecards, Sentry release health, OpenTelemetry semantic
   conventions for CI/CD.

## What to find out for each significant solution

- Licence, model (open source / open core / SaaS), activity in 2025–2026,
  who actually uses it.
- **Unit of assessment**: a run, a commit, a pull request, a release, a
  service?
- **Scoring**: a transparent formula or ML / heuristics? Can a user configure
  weights and thresholds, and what does the configuration look like?
- **Separating new from old**: a baseline, a new code period, finding
  fingerprints, comparison with N earlier runs. How is a risk accepted
  (suppress / accept); does an acceptance expire, is there an audit trail?
- **Flaky tests**: how they are detected, quarantine, automatic release from
  quarantine, effect on the gate.
- **Classifying and grouping failures**: product bug / test bug /
  environment; rules (regex) versus ML; what is deterministic.
- **Statistics**: which comparison methods (percentiles, median + MAD,
  Mann-Whitney, confidence intervals), minimum sample size, how environment
  noise is handled.
- **Ownership**: linking issues to components, teams, services.
- **Outputs**: reports, pull request comments, badges, dashboards, metrics
  (Prometheus / OpenMetrics), machine-readable formats.
- **Weak spots**: what users complain about (noise, false positives, the gate
  being ignored, configuration complexity).

## Result format

1. **Market map** — solutions by class, open source apart from commercial,
   the leaders of each class.
2. **Comparison table**, "solution × capability": gate on new code, risk
   acceptance with an expiry, flaky / quarantine, failure classification,
   statistical comparison, configurable thresholds, test effectiveness,
   ownership, export formats.
3. **Top 15 ideas worth taking**, each with: source (with a link), essence,
   the problem it solves, how transparent and reproducible it is, whether it
   needs a server / database or works on files in CI, implementation size
   (S/M/L).
4. **Antipatterns** — approaches that do not work in practice, with examples
   and reasons.
5. **Trends 2025–2026** — where the market is going (AI triage, test impact
   analysis, flaky management, policy as code, and so on).
6. **Recommendations** for a small open-source test automation framework that
   runs in CI without a server of its own and wants transparent, explainable
   assessments.

## Rules

- Back every claim with a link to official documentation, source code, an
  issue or a publication; mark outdated sources.
- Do not invent capabilities: if there is no information, say so.
- Write in Russian; keep product names, terms and configuration keys in the
  original.
