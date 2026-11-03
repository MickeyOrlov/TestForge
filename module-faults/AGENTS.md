# module-faults: working on this module

Read root AGENTS.md and README.md first. May depend on core only.

- Toxiproxy owns TCP proxying and toxic semantics; never implement a proxy here.
- Bean creation is inert. Only an explicit scope creates a proxy/container.
- Every scope owns a unique proxy, port and toxic namespace. Never reset a shared controller.
- Validate exact host:port allowlisting before any controller call. Names contain no credentials.
- Always close scopes. Delete only owned proxies; failed deletion is surfaced and retryable.
- Evidence never includes payloads or upstream addresses; sink failures never mask cleanup failures.
- External controllers have no TTL: JVM hard kills/network partitions require operator cleanup.
- All Docker examples are tagged containers; the default build never starts Docker.

Verify module tests, architecture tests and the full build. Run FaultsToxiproxyIT explicitly for native acceptance.
