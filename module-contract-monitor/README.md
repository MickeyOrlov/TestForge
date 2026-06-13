# module-contract-monitor

CI-friendly Kafka contract drift monitoring.

## What's inside

- **`ContractMonitorCase`** — one named check: Kafka filter + payload
  contract.
- **`JsonPayloadContract`** — adapter over `MessageContract` and
  `SchemaContract`.
- **`ContractMonitorRunner`** — runs all registered cases, writes artifacts and
  fails loudly from JUnit via `assertHealthy()`.
- **`PayloadShapeNormalizer`** — converts JSON into `jsonPath -> type` shape
  snapshots. Values are not stored in shape files.
- **`ContractMonitorReport`** — structured result with contract violations,
  missing messages, shape diffs and artifact paths.

This module composes `module-kafka` and `module-contract`. It is deliberately
separate so those lower-level modules stay deletable.

## Configuration

```yaml
forge:
  contract-monitor:
    enabled: true
    output-dir: build/contract-monitor/current
    baseline-dir: build/contract-monitor/baseline
    fail-on-contract-violation: true
    fail-on-shape-diff: true
    fail-on-missing-message: true
```

## Usage

```java
@Bean
ContractMonitorCase partnerStatusMonitorCase() {
    return new ContractMonitorCase(
            "partner-status",
            KafkaMessageFilter.builder()
                    .topic("partner.events")
                    .key("request-123")
                    .build(),
            JsonPayloadContract.of(MessageContract.named("partner-status")
                    .required("$.eventId", FieldType.STRING)
                    .required("$.payload.status", FieldType.STRING)
                    .build()));
}

@SpringBootTest
class ContractMonitorIT {

    @Autowired ContractMonitorRunner monitor;

    @Test
    void kafkaContractsDidNotDrift() {
        monitor.assertHealthy();
    }
}
```

Artifacts are written under `forge.contract-monitor.output-dir`:

- `<case>.shape.json` — payload shape only, no values
- `<case>.message.json` — Kafka envelope with redacted payload shape
- `report.json`
- `report.md`

If no baseline shape exists, the current shape is written and shape diff does
not fail. Contract violations and missing messages still follow their fail
flags.

## What this is — and is not

This is a QA-side drift monitor for real staging traffic. It is not
consumer-driven contract testing: no provider verification, no pact files, no
schema evolution workflow. Use Pact or Spring Cloud Contract when teams need a
formal provider/consumer contract.

## Agent notes

- Register monitor cases as Spring beans; do not add YAML magic for Java DSL
  contracts unless a project explicitly asks for it.
- Keep Kafka access profile-specific. The module can be on the classpath while
  `forge.contract-monitor.enabled=false`.
- Preserve redaction: shape snapshots must never store actual payload values.
- Baseline artifacts are CI inputs/outputs, not files rewritten in
  `src/test/resources` by default.
