# Lighthouse workflow

Read root AGENTS.md and README.md. This is project tooling, not a production module.
- Keep pinned native LHCI; never calculate performance metrics/threshold verdicts here.
- Explicit JSON config, URL file and Chrome; fresh working directory per invocation.
- Only filesystem export, including after assertion failures. No public upload/status updates.
- No default-build traffic or implicit browser download.
- Preserve native results and distinguish execution failure from assertion failure.
- Native reports are sensitive; do not claim sanitization or add them to git.
Verify offline tests and native passing/failing acceptance with an owned loopback server.
