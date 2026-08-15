# module-api-fuzz: prototype analysis and engine decision

This document records why `module-api-fuzz` delegates fuzz generation to
[Schemathesis](https://schemathesis.readthedocs.io/) instead of continuing the
home-grown Java fuzz engine prototyped on `codex/module-api-fuzz-v1` …
`v1.4`. It is written for reviewers who remember the prototype and want to know
what happened to it.

## Summary

The prototype reached 48 production classes and ~5,700 lines of main Java
(~13,000 lines including tests and docs). Roughly 41 of those classes existed
only because TestForge was reimplementing property-based API testing in Java.
Schemathesis 4.x already owns that problem, is actively maintained, and is the
kind of established tool TestForge is supposed to compose rather than replace.

The new module is an **adapter**: configuration, safety policy, command
construction, process execution, report ingestion, and Spring wiring. There is
no second fuzz engine.

## Replace vs reuse

| Capability | Prototype (v1.4) | Schemathesis 4.24.3 | Final owner |
|---|---|---|---|
| Schema-aware value generation | `JsonBodyFactory` (408 LOC), `ConstraintAwareValueFactory`, `SchemaFacts` | Hypothesis-backed generation from OpenAPI | **Schemathesis** |
| Boundary / negative mutation | `SchemaMutations` (273), `JsonBodyMutator`, `BodyCaseGenerator`, `Compositions` | `--mode {positive,negative,all}`, coverage phase | **Schemathesis** |
| Case selection / budgeting | `FuzzCaseGenerator`, `FuzzCaseSelector`, `FuzzCase*` | `--max-examples`, phase selection | **Schemathesis** |
| Shrinking / minimization | `RequestShrinker` (468), `ShrinkOutcome` | Hypothesis shrinking (`--no-shrink` to disable) | **Schemathesis** |
| Constraint bookkeeping | `ConstraintInventory` (185), `ConstraintCoverage`, `DeclaredConstraint`, `UnsupportedConstraint` | internal to generation | **Schemathesis** |
| Protocol-level mutation | `ProtocolCaseGenerator` | coverage phase scenarios | **Schemathesis** |
| Response classification | `ResponseClassifier` (181), `FuzzVerdict`, `HttpFacts` | named checks (`not_a_server_error`, `response_schema_conformance`, …) | **Schemathesis** |
| Finding confirmation / flake control | `FindingConfirmer`, `ConfirmationResult`, `ControlResult`, `BaselineSelfCheck` | Hypothesis reproduction + `--max-failures` | **Schemathesis** |
| Reproduction artifacts | `ReproductionWriter` (146), `ReproductionManifest`, `Reproducibility`, `SpecFingerprint` | `--seed`, VCR cassette, `st replay` | **Schemathesis** |
| Stateful sequencing | not implemented (planned) | `--phases stateful`, response-derived data | **Schemathesis** (opt-in) |
| Parameter serialization | `ParameterSerialization` (172), `SerializingValueResolver`, `BodyPaths`, `BodyPlan` | internal to generation | **Schemathesis** |
| Spring auto-configuration | `TestForgeApiFuzzAutoConfiguration` | — | **TestForge** |
| Properties + defaults | `ApiFuzzProperties` | — | **TestForge** |
| Safe-method policy | `methods` + `allow-unsafe-methods` | partial (see below) | **TestForge** |
| Spec registry integration | reuses `forge.api-discovery.specs` | — | **TestForge** |
| Artifact locations / run evidence | `outputDir`, `FuzzReportMarkdown` | raw reports only | **TestForge** |
| Process execution seam | n/a (in-process) | — | **TestForge** |

### Explicitly not reimplemented

`BodyCaseGenerator`, `JsonBodyFactory`, `JsonBodyMutator`, `SchemaMutations`,
`RequestShrinker`, `ConstraintInventory`, `ProtocolCaseGenerator`,
`ResponseClassifier`, `FindingConfirmer`, `ReproductionWriter` and their
supporting value types have **no successor** in the new module. If a future
change appears to need one of them, that is a signal to configure Schemathesis
differently, not to restart the engine.

## Safety: the finding that shaped the design

TestForge's rule is that mutation methods require two keys — the method listed
**and** `allow-unsafe-methods=true`. Enforcing that on top of Schemathesis
takes **two mechanisms, not one**.

Passing only `--include-method GET` is **not sufficient**. Schemathesis's
coverage phase, in negative or `all` generation mode, emits
"unspecified HTTP method" cases whose method is overridden *after* operation
selection. Verified against a local server on 4.24.3:

```
st run spec.yaml -u http://127.0.0.1:8732 --include-method GET \
   --phases coverage -m all -n 30 --seed 99
→ verbs observed at the server: GET, GET, TRACE
```

The generated set is `DEFAULT_UNEXPECTED_METHODS - methods declared on that
path`, where

```python
DEFAULT_UNEXPECTED_METHODS = {"get","put","post","delete","options","patch","trace","query"}
```

so a spec shaped differently can produce `PUT`, `PATCH` or `DELETE` from a
configuration that lists only `GET`.

The module therefore also writes `unexpected-methods = []` into the
Schemathesis config file it generates whenever unsafe methods are not allowed:

```toml
[phases.coverage]
unexpected-methods = []
```

Re-verified with the same seed: only `GET` reaches the server.

Two consequences for reviewers:

- the safe-method guarantee is a property of **command + generated config
  together**; a test that only asserts on `--include-method` does not prove it;
- the generated config file must always be passed explicitly with
  `--config-file`. Schemathesis otherwise auto-discovers `schemathesis.toml`
  by walking up parent directories, so a stray file in a developer's home tree
  could silently change generation behaviour.

## Secrets

`--report ndjson` records the **full command line** in its `Initialize` event:

```json
{"Initialize": {"command": "st run spec.yaml -u http://… --include-method GET …",
                "schemathesis_version": "4.24.3", "seed": 99}}
```

A token passed as `-H "Authorization: Bearer …"` would therefore be persisted
into a TestForge artifact, not merely printed to a log. Authentication is
consequently expressed in the Schemathesis config file with environment
substitution, which the tool resolves through `Template.substitute(os.environ)`:

```toml
[auth]
bearer = "$STAGING_TOKEN"
```

TestForge never places credentials in process arguments, never logs environment
values, and never copies the user's auth config into its own artifacts.

## Result classification

Exit status alone cannot classify a run:

| Situation | Exit code |
|---|---|
| no findings | 0 |
| API findings | 1 |
| target unreachable | 1 |
| bad usage / missing spec | 2 |

Findings and infrastructure failure share exit code 1, so classification is
driven by the NDJSON event stream instead. A `NonFatalError` event marks an
execution problem; check failures inside `ScenarioFinished` mark API findings.
This is why the module consumes structured reports and never parses the
rendered terminal output.

## Stateful testing

The old roadmap anticipated TestForge building producer/consumer inference on
top of `module-api-explorer`. Schemathesis already provides stateful testing
and response-derived values via `--phases stateful`. V1 exposes that phase as
an opt-in capability and builds no inference engine. `module-api-explorer` is
not modified by this work.

## Version policy

Developed and verified against **Schemathesis 4.24.3** (latest stable at the
time of writing, confirmed against PyPI). The module records the version
reported by the executable in every run's evidence and does not bundle,
install, or require Python at build time.
