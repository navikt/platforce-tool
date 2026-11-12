---
name: platforce-project-map
description: Orient in the platforce-tool Kotlin/http4k application, understand its package and GUI boundaries, and follow user-scoped request/cache patterns. Use when starting an unfamiliar task, tracing API wiring, or changing cross-cutting behavior.
---

# platforce-tool project map

Use this skill to orient before changing multiple parts of the application. Keep the existing package boundaries and follow `.github/copilot-instructions.md`.

## Application structure

- `src/main/kotlin/no/nav/platforce/tool/Application.kt` wires clients, scanners, and http4k routes.
- Feature code is grouped under `nais`, `github`, `repositories`, `targets`, `resolution`, `security`, `scanning`, `pullrequests`, `ignore`, `notes`, `user`, and `db`.
- GraphQL operations for Nais live in `src/main/resources/graphql/`.
- The GUI is a classic-script frontend under `src/main/resources/gui/`; its feature files are in `gui/js/`.
- Kotlin tests mirror the feature packages in `src/test/kotlin/no/nav/platforce/tool/`; browser-like GUI tests are in `src/test/js/`.

## Request and user state

- GUI API handlers that access user data should obtain the identity through `request.userContext()`.
- `UserContext` is keyed by the JWT `preferred_username`; its stores and selected-team state belong to that user.
- Keep cache reads, writes, progress, refresh locks, and invalidation user-scoped. Do not introduce global caches for user-specific data.
- When changing a route, inspect the frontend caller as well as the route and preserve the established response shape.

## Working and validating

- Trace route registration in `Application.kt`, then inspect the feature API and its service/store rather than adding parallel wiring.
- Prefer focused tests in the matching Kotlin package and `node --test src/test/js/<feature>.test.js` for GUI behavior.
- Run all backend tests with `./gradlew test`; run all frontend tests with `node --test src/test/js/*.test.js`.
- Kotlin formatting and lint tasks are `./gradlew formatKotlinMain formatKotlinTest lintKotlin`.

