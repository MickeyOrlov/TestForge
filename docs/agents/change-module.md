# Playbook: change an existing module

1. **Read** the module's `AGENTS.md` (scope, allowed dependencies,
   invariants) and its README. If the change breaks an invariant, stop and
   raise it as a design question instead of editing the invariant away.
2. **Find the living example** listed under *Tests* in that `AGENTS.md`.
   That example is the behaviour contract users copy.
3. **Write the failing test first** — in the module's own `src/test` for
   logic, in `example-tests` for user-visible behaviour. A bug fix starts with
   a test that reproduces it.
4. **Change the code.** Keep the diff inside the module. Touching `core` is
   justified only when a second module needs the same thing now.
5. **Keep defaults safe.** A new option is off/conservative by default; a
   new failure mode fails closed rather than passing silently.
6. **Update docs in the same commit:** module README, the example test, and
   the module `AGENTS.md` if scope or invariants moved. Root `AGENTS.md`
   *Known sharp edges* if users can now cut themselves on something new.
7. **Verify:**

   ```bash
   ./gradlew :<module>:test :architecture-tests:test
   ./gradlew build
   ```

   Run the tagged suites (`containersTest`, `browsersTest`) when the change
   touches what they cover, and say in the PR if you could not.
