---
name: platforce-target-security
description: Change target versions, Gradle dependency resolution, OSV vulnerability enrichment, plugin security, BOM coverage, or upgrade candidate scanning in platforce-tool. Use for Target Versions security scans and their caches or prerequisites.
---

# Target resolution and security

## Main implementation areas

- `targets/TargetVersionsStore.kt` and `targets/TargetVersionsApi.kt` own saved target state and update handling.
- `resolution/GradleTargetResolutionScanner.kt` coordinates per-user resolution jobs; `GradleTargetResolutionService.kt` performs Gradle resolution.
- `security/TargetSecurityScanner.kt` and `TargetSecurityService.kt` combine resolved versions with OSV data.
- Plugin checks live in `security/PluginSecurity.kt`; candidate upgrade scans are in `targets/UpgradeSecurityScanner.kt`.
- Shared scan job lifecycle and user/fingerprint isolation are in `scanning/UserScanJobs.kt`.
- The target UI is in `src/main/resources/gui/js/target-security.js` and `target-upgrades.js`.

## Behavior and invariants

- Target state is user-owned. Keep scans, status, cached results, and invalidation scoped by username and the target-state fingerprint.
- Updating targets invalidates dependent resolution and security results. Inspect `targets/TargetVersionsApi.kt` and preserve invalidation across scanners when adding a new derived result.
- Normal target resolution excludes platform BOM targets. BOM coverage is a separate scan and requires a READY normal resolution and matching normal security baseline; preserve the prerequisite in `resolution/GradleTargetResolutionScanner.kt`.
- Security scans enrich resolved dependencies with OSV vulnerability information. Keep direct/transitive resolution evidence and override/coverage semantics intact; do not infer safety from a target version string alone.
- Scans expose `IDLE`, `RUNNING`, `READY`, and `FAILED` states. Preserve errors and readiness/status behavior across backend responses and GUI polling.

## Tests

- Focused security and resolution tests include `UpgradeSecurityScannerTest`, `TargetSecurityFlowTest`, `PlatformBomSecurityFlowTest`, `PluginSecurityFlowTest`, `GradleResolutionReportTest`, and `UserScanJobsTest`.
- Run relevant tests with `./gradlew test --tests '*TargetSecurityFlowTest' --tests '*PlatformBomSecurityFlowTest' --tests '*PluginSecurityFlowTest'`.
- Frontend tests covering target behavior include `node --test src/test/js/target-upgrades.test.js src/test/js/security-refresh.test.js`.

