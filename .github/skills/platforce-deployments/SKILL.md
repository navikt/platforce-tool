---
name: platforce-deployments
description: Change Nais deployment discovery, repository association, deployment freshness, job display, or GitHub rebuild workflow detection and generation in platforce-tool. Use for Deployments tab, rebuild PRs, monorepos, and deploy-job dispatch behavior.
---

# Nais deployments and rebuild workflows

## Main implementation areas

- `nais/NaisConsoleClient.kt` performs Nais Console queries.
- `nais/NaisDeploymentDiscovery.kt` maps applications and jobs to `RunningApplication` records, resolves repository provenance, and includes image and deployment-activity metadata.
- GraphQL query shapes are in `src/main/resources/graphql/deployment-*.graphql`.
- `nais/DeploymentsApi.kt` and `nais/DeploymentRefreshApi.kt` expose deployment and per-user refresh-marker APIs.
- `nais/RebuildWorkflow.kt`, `nais/RebuildPullRequestService.kt`, and `pullrequests/` implement GitHub workflow detection and generated rebuild PRs.
- The frontend is `src/main/resources/gui/js/deployments.js`; related behavior tests are `src/test/js/deployments.test.js`.

## Behavior to preserve

- The shared `RunningApplication` model represents both applications and jobs; jobs use `workloadType = "JOB"`. Keep workload-specific behavior explicit rather than assuming every row is an application.
- Application discovery includes running instances; job discovery uses the jobs query and can have image metadata without running-instance counts.
- Repository association and deployment/activity timestamps are separate concepts. Preserve the provenance fields and the existing freshness rules when changing how repositories or timestamps are resolved.
- Filter repositories using GitHub App access and the user's ignored-repository state before exposing repository actions.
- Workflow metadata is fetched separately from the initial deployment data so application rows can render while workflow checks progress. Preserve progressive updates, bounded work, and refresh behavior.
- Rebuild workflows support ordinary repositories and monorepo variants, including app+cluster, app+cluster+namespace, and fixed-app cluster+namespace targets. Inspect `RebuildWorkflow.kt` and `RebuildPullRequestService.kt` before changing target encoding or YAML generation.
- Jobs are displayed with a job indicator. Keep them out of application rebuild targets; repositories with job instances can also dispatch `.github/workflows/deploy-job.yml` without inputs.
- Target-copy controls apply to application targets. Keep copy-command behavior consistent for individual repositories and the aggregate `gh all` action.

## Tests

- Run frontend deployment tests with `node --test src/test/js/deployments.test.js`.
- Run focused backend tests with `./gradlew test --tests '*NaisDeploymentDiscoveryTest' --tests '*RebuildWorkflowTest' --tests '*RebuildPullRequestServiceTest'`.
- Use `src/test/kotlin/no/nav/platforce/tool/nais/DeploymentRefreshApiTest.kt` for refresh-marker API changes.

