# platforce-tool [<img align="right" src="https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png" width="18" alt="GitHub repository" />](https://github.com/navikt/platforce-tool)
Selfservice console for operating on Salesforce middleware apps

## Code structure

`Application.kt` constructs services and composes routes; `Bootstrap.kt` is the
entry point. Feature code lives under `src/main/kotlin/no/nav/platforce/tool`:

| Package | Responsibility |
| --- | --- |
| `repositories` | Repo inventory, comparisons, scanner views, cache and API |
| `targets` | Target defaults, state, persistence, fingerprints and API |
| `resolution` | Gradle project generation, execution, results, timings and debug files |
| `security` | OSV integration, target security evaluation, scan results and API |
| `pullrequests` | Build/wrapper updates and GitHub PR orchestration |
| `gradle` | Build and wrapper file parsing |
| `nais`, `github` | External clients and GitHub App authentication |
| `scanning` | Shared user-isolated asynchronous scan jobs |
| `user`, `notes`, `ignore` | User context, selected team, notes and ignored repos |
| `internal`, `config`, `db` | Operational routes, configuration and database tables |

Services receive collaborators explicitly rather than accessing a global
Application instance. Domain and API model classes live alongside their feature.
Cross-feature regression scenarios and test doubles live in `src/test/kotlin`
under `workflows`; focused client, route and view tests use matching packages.

Frontend features live in `resources/gui/js`: `api`, `state`, `shared`, `scanner`,
`targets`, `target-security`, `scan-status`, `ignored` and `bootstrap`. `index.html`
loads these as ordered classic scripts, sharing state and preserving existing
inline handlers without a build tool; bootstrap runs last.

### Typical user flow

#### GUI files

![GUI files and their roles in startup, editing, scanning and rendering](docs/gui-flow.svg)

The GUI scripts share browser scope. `bootstrap.js` wires interactions and
startup, `state.js` holds shared state, and the feature files own rendering and
user actions. `api.js` contains common requests; scan orchestration, PR creation,
individual refresh and target saving also make requests in their feature files.
The diagram shows responsibilities rather than an ES-module dependency graph.

`styles.css` is grouped into page/navigation, common controls, repository cards,
summary badges, findings/untracked rows, target editing, scan progress, security
details and visibility utilities. Existing rule overrides retain their order.

#### Backend packages

![Package flow from dependency scan through security enrichment to pull request](docs/user-flow.svg)

The diagram follows **Refresh scan → Target Versions SCAN → enriched repository
view → Create PR**. Repository inventories are cached per user; comparison and
security enrichment happen on reads, not by rewriting the inventory cache.
Security scanning resolves the saved target dependencies, not each repository's
build. When it completes, the GUI reloads repository data without another
GitHub file scan. PR creation uses that same current comparison/enrichment path.

Saving targets invalidates that user's resolution and security results. A
matching completed Gradle resolution can be reused, while an explicit security
scan refreshes OSV findings. The diagram shows the successful journey; scan
failures are exposed to the GUI and obsolete jobs cannot publish results.

## Application behavior

Platform BOMs have a separate target section below Gradle and a separate
repository finding type. Literal `platform(...)` and `enforcedPlatform(...)`
coordinates are discovered as untracked until saved as a target. Click an
UNTRACKED or AHEAD pill to draft the version, then save Target Versions.
Tracked BOMs use the same OK / UPDATE / AHEAD comparisons and PR updates as
other versioned entries. In this iteration BOMs are not included in target
security resolution or vulnerability enrichment.

### Local mock GUI

Run `main` in
`src/test/kotlin/no/nav/platforce/tool/localdev/LocalFrontendMain.kt` using the
green arrow in IntelliJ, or run `./gradlew runLocalFrontend`. Open
http://localhost:8080/internal/gui. An optional first program argument selects
a different port. The server binds only to IPv4 loopback.

This serves the original HTML, CSS and JavaScript directly from
`src/main/resources/gui`; edit and reload without rebuilding. No React, Node
build, credentials, database or external API access is required. Mock data
includes multiple teams, version differences, missing app access, unscanned
repositories, synthetic vulnerabilities and transitive overrides.

Targets, notes, ignored repositories and selected team are kept in memory until
restart. Refresh and security scanning are simulated; Create PR opens a local
confirmation page without contacting GitHub. Fixtures live in
`LocalFrontendFixtures.kt`. This test-source entry point is excluded from the
production artifact and does not modify production routing or authentication.

The GUI at `/internal/gui` lists repositories from NAIS Console, compares their
Gradle dependencies with user-maintained targets, scans target versions using
Gradle resolution and OSV, and creates dependency update pull requests.

The HTTP surface consists of the GUI and its API calls, operational endpoints
(`/internal/isAlive`, `/internal/isReady`, `/internal/metrics`, `/internal/hello`,
`/internal/files`), and database administration (`/internal/clearDb`,
`/internal/initDb`, `/internal/add-transient`).
