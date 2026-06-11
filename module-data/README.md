# module-data

Small helpers for data-heavy tests.

## What's inside

- **`RunUniqueValues`** — per-JVM registry that prevents generated values from
  being reused in parallel scenarios.
- **`TemplateRenderer`** — expands `%{variable}%` placeholders with cycle and
  missing-variable diagnostics.
- **`Generators`** — mask presets (`guid`, `phone(prefix, digits)`, `numeric`,
  `alphanumeric`) meant to be passed to `RunUniqueValues.generate(...)`.

The module does not ship **domain** Object Mothers (order payloads, user
profiles, product cards). Add those in the adapted project as small static
factories or builders; use `RunUniqueValues` + `Generators` for uniqueness and
masks, `TemplateRenderer` for payloads that reference scenario variables.

## Configuration

```yaml
forge:
  data:
    max-template-depth: 10
```

## Usage

```java
String phone = uniqueValues.generate("phone", Generators.phone("+8499", 7), 20);
String id = uniqueValues.generate("requestId", Generators.guid(), 10);
String body = templateRenderer.render("{\"id\":\"%{requestId}%\"}", variables);
```
