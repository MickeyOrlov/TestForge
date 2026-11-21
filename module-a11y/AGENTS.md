# module-a11y: working on this module

Read root AGENTS.md and this module's README. May depend on core only; takes an existing Page.
- Deque axe-core owns WCAG rules, scanning and native severity. Never implement rules here.
- Enabling constructs beans only, never a browser. All native examples carry browser tags.
- Keep page keys explicit and query-free. Never serialize raw axe results/HTML, form values or page URLs.
- Baseline mutation requires explicit writeBaseline. Changed scan policy fails closed.
- New selectors and impact escalation remain new; acceptance requires reason and expiry.
- Incomplete/manual-review results never pass or become a baseline.
- Artifact failures do not replace scan or assertion failures.
Verify module/architecture tests, full build and opt-in A11yAxeIT with a real browser.
