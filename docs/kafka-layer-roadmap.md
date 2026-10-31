# Kafka layer roadmap

`module-kafka` waits for a message by topic, key, header or JSON path. A
test of an event-driven system also has to prove what was *not* sent and
that nothing was sent twice, start a flow by publishing an event itself, and
explain a failed test with the messages around it.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Assertions beyond "it arrived"** — exactly one (duplicates), none within a window, every match; a timeout that names the closest messages and failed criteria | `module-kafka` | shipped |
| 2 | **Publishing test events** — send a JSON event with the scenario's scope and correlation headers, so the flow under test is started like production starts it | `module-kafka` | shipped |
| 3 | **Messages in failure diagnostics** — what the test published and what arrived on its topics, in the run index | `module-kafka` | shipped |
| 4 | **Review** — close the gaps a review of the module finds | `module-kafka` | planned |
