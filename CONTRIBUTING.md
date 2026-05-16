# Contributing

Thanks for your interest. A few quick notes to keep PRs reviewable.

## Building

```
./gradlew build
```

JDK 21+. Loom downloads ~3 GB of Minecraft + mappings on first run. Subsequent
builds are fast.

## Tests

```
./gradlew :core:test
```

Tests live in `core/src/test/kotlin`. They run against a `MockWebServer` for
LLM calls and an in-memory SQLite file per test. There are no tests against a
live Minecraft instance — those are out of scope for this repo.

When you add a tool, please also add:
- A unit test that decodes a sample `arguments` payload.
- A guardrail test if it introduces a new bucket.
- A director integration test if it crosses a code path not covered already.

## Style

- Kotlin 2.0, JDK 21 target.
- One top-level declaration per file unless they are tiny and tightly coupled.
- Prefer constructor injection over service locators. The two holders we keep
  (`ServerActionsHolder`, `SensorsHolder`) exist because the platform module
  has to install them before common code runs — they are not a general pattern.
- The `core` module is pure Kotlin and must not import `net.minecraft.*` or
  any platform API. Minecraft-bound code belongs in `common` (or a platform
  module); the Bukkit-bound code belongs in `paper`.

## Commit messages

Conventional Commits is welcome but not enforced. Just be specific about *why*
something changed — the diff already shows the *what*.
