# module-data

Small helpers for data-heavy tests.

## What's inside

- **`RunUniqueValues`** — per-JVM registry that prevents generated values from
  being reused in parallel scenarios.
- **`TemplateRenderer`** — expands `%{variable}%` placeholders with cycle and
  missing-variable diagnostics.

The module does not ship domain generators. Add those in the adapted project
and use `RunUniqueValues` as the uniqueness guard.

## Configuration

```yaml
forge:
  data:
    max-template-depth: 10
```

## Usage

```java
String phone = uniqueValues.generate("phone", phoneGenerator::next, 20);
String body = templateRenderer.render("{\"id\":\"%{requestId}%\"}", variables);
```
