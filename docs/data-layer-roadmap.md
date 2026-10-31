# Data layer: deep development plan

Every test on a shared environment needs data that is readable, unique, never
seen by a neighbouring test and gone afterwards. `module-data` already has the
pieces (`RunUniqueValues`, `Generators`, `TemplateRenderer`, the `@Prepared`
pool, `Cleanup`); this plan turns them into one path a test author follows.
Each step ships and merges on its own.

| Step | What a test author gets | Where | Status |
|---|---|---|---|
| 0. Cleanup registry | undo what a test created, LIFO, even when it failed (`Cleanup`, `CleanupExtension`) | `module-data` | shipped |
| 1. Factories | domain objects and payloads with readable, unique defaults, overridable per field, with named traits (`Factory`) | `module-data` | shipped |
| 2. Isolation | a run tag and a per-test tag inside generated values, so parallel tests and parallel runs never see each other's data; factories that create on the environment register their own cleanup | `module-data` | planned |
| 3. Leftover report | what cleanup could not undo, in the run index (`module-data/leftovers.json`) with the run tag, so a sweeper or a human removes it | `module-data` | planned |

## Boundaries

- The module still ships no domain Object Mothers. `Factory` is the generic
  mechanism; the adapted project declares `customers`, `orders`, … with it.
- No reflection beyond reading record components: a factory for a class that
  is not a record takes an explicit assembler.
- Isolation tags are plain strings inside values a test already sends; nothing
  depends on the system under test understanding them.
