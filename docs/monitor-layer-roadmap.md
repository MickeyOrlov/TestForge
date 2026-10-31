# Contract monitor roadmap

`module-contract-monitor` checks real Kafka traffic against a contract and a
shape baseline. Judged on one message, it was both noisy and blind; judged on
a sample, it still needed to say which drift matters to consumers.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **A sample, not one message** — the newest N matching messages validated and their shapes merged | `module-contract-monitor` | shipped |
| 2 | **Drift impact** — each shape change judged for consumers; fail only on breaking drift | `module-contract-monitor` | shipped |
| 3 | **Review** — close the gaps a review of the module finds | `module-contract-monitor` | planned |
