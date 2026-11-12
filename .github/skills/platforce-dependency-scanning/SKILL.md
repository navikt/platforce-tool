---
name: platforce-dependency-scanning
description: Implement or debug repository dependency inventories, Gradle file parsing, scan progress and caching, ignored dependencies, and dependency pull requests in platforce-tool. Use for the Dependency Scanner tab and repository scan APIs.
---

# Repository dependency scanning

## Main implementation areas

- `repositories/DependencyScanner.kt` fetches repository build files and creates inventories.
- Gradle declarations and wrapper versions are parsed by `gradle/GradleDependencyParser.kt` and `gradle/GradleWrapperParser.kt`.
- `repositories/DependencyScanCache.kt`, `DependencyScanViewService.kt`, and `DependencyScanApi.kt` manage per-user scan state, progress, comparison, and refresh endpoints.
- `pullrequests/DependencyPullRequestService.kt` creates repository upgrade PRs.
- The scanner UI is in `src/main/resources/gui/js/scanner.js`; shared GUI API helpers are in `gui/js/api.js`.

## Data and cache invariants

- Repository inventory data is parsed from GitHub source files, then compared with the current `TargetVersionsState` to produce scan findings. Keep parsing, inventory creation, and comparison responsibilities distinct.
- Repository scans deliberately exclude `testImplementation` and `testRuntimeOnly`; BOM declarations are stored separately from ordinary dependencies. Preserve declaration metadata if changing how inventories are constructed.
- `DependencyScanCache` is indexed by username and keeps refresh progress and refresh locks per user. Do not share scan results or progress between users.
- Full and single-repository refreshes update the same user's cache. Preserve locking and progress semantics when changing asynchronous or incremental refresh behavior.
- Scan views can be enriched by target security results. Check `DependencyScanViewService` before duplicating security lookups or changing finding status logic.
- When changing cached inputs or derived scan results, inspect `DependencyScanIsolationTest`, `ScanCacheTest`, and `SecurityEnrichmentCacheTest`; update invalidation behavior and tests together.

## Tests

- Relevant Kotlin tests include `DependencyFlowTest`, `DependencyScanIsolationTest`, `IgnoredDependenciesTest`, `ScanCacheTest`, and `SecurityEnrichmentCacheTest` under `src/test/kotlin/no/nav/platforce/tool/workflows/`.
- Run focused tests with `./gradlew test --tests '*DependencyFlowTest' --tests '*DependencyScanIsolationTest' --tests '*ScanCacheTest'`.
- Run frontend scanner tests with `node --test src/test/js/gui-structure.test.js src/test/js/ignored-dependencies.test.js`.
