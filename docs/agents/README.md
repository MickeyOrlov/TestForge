# Agent playbooks

Step-by-step procedures for recurring kinds of change. They are plain Markdown
on purpose: any coding agent (or human) can follow them, no tool-specific
format required.

| Playbook | Use when |
|---|---|
| [add-module.md](add-module.md) | adding a new `module-*` to the template |
| [change-module.md](change-module.md) | changing behaviour of an existing module |
| [review-module.md](review-module.md) | assessing a module's maturity or a change to it |
| [../adaptation-checklist.md](../adaptation-checklist.md) | adapting a fresh clone to a real project |

Every module also has its own `AGENTS.md` with scope, allowed dependencies,
invariants and test locations. Read it before touching the module.

The rules these playbooks rely on are enforced by `architecture-tests`; a
playbook step that disagrees with a red guardrail is the step that is wrong.
