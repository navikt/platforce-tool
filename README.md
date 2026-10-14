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

The plain HTML/CSS interface uses Aksel-inspired controls, focus treatment and
spacing, with a compact layout and text-labelled status colours retained.
`styles.css` centralizes a light palette based on the Aksel `--ax-*` token
values (https://aksel.nav.no/grunnleggende/styling/design-tokens) under local
`--ui-*` variables. It does not load Aksel React components, fonts or CDN assets;
Source Sans is used if installed, otherwise the system font. This is an
Aksel-aligned application stylesheet, not the official component library or a
claim of full accessibility compliance.

## Application behavior

### Local frontend development

Run `main` in
`src/test/kotlin/no/nav/platforce/tool/localdev/LocalFrontendMain.kt` using the
green arrow in IntelliJ, or run `./gradlew runLocalFrontend`. Open
http://localhost:8080/internal/gui. The server binds only to IPv4 loopback;
an optional first program argument selects another port in IntelliJ.

This entry point does not construct `Application`, use authentication or a
database, or call GitHub, NAIS, Gradle or OSV. It is test-source code and is
excluded from the production artifact. Demo repositories include multiple teams,
missing app access, an unscanned repository, untracked dependencies, version
differences, synthetic vulnerabilities and transitive overrides. The security
data is illustrative, not a real security assessment.

Targets, notes, ignored repositories and selected team are stored in memory
until restart. Refresh updates synthetic inventories, SCAN simulates polling
through RUNNING to READY, and Create PR opens a local confirmation page without
creating anything externally. Repository comparison and security enrichment
reuse the production logic. Fixtures live in `LocalFrontendFixtures.kt`.
HTML, CSS and JavaScript are read directly from `src/main/resources/gui`:
edit and reload the browser without rebuilding. The local entry point finds
that directory from the working directory or its ancestors.

### Production behavior

The GUI at `/internal/gui` lists repositories from NAIS Console, compares their
Gradle dependencies with user-maintained targets, scans target versions using
Gradle resolution and OSV, and creates dependency update pull requests.

The HTTP surface consists of the GUI and its API calls, operational endpoints
(`/internal/isAlive`, `/internal/isReady`, `/internal/metrics`, `/internal/hello`,
`/internal/files`), and database administration (`/internal/clearDb`,
`/internal/initDb`, `/internal/add-transient`). Experimental debug, Dependabot,
SBOM and deps.dev endpoints are no longer exposed. User authentication for the
GUI is handled by the NAIS Wonderwall sidecar.

Repository inventories, refresh progress, target resolution and security scan
results are cached separately for each authenticated `preferred_username`.
Repository comparisons are calculated from the cached inventory and the user's
current targets on every read. Completing a security scan reloads the repository
view immediately; it does not require another repository scan.

Target changes invalidate only that user's resolution and security results.
Fingerprints include dependency, plugin and Gradle versions as well as transient
dependency flags. Obsolete scan jobs cannot publish results after invalidation.
Explicitly running SCAN again refreshes OSV information even when targets have
not changed; ordinary reads reuse the latest matching result. Caches are
in-memory and reset on deployment. The GitHub App installation token and Gradle
distribution/artifact downloads are infrastructure resources, not user scan
results.

Run backend regression tests with `./gradlew test` and GUI flow tests with
`node --test src/test/js/*.test.js`. Neither requires Wonderwall or live services.

`/internal/files` shows debug artifacts from the signed-in user's latest
executed Gradle resolution. Each run has a separate directory under
`/tmp/files/<hashed-user>/<run-id>`; the browser cannot access other users'
directories. These files are ephemeral and disappear on deployment.

The artifacts include the generated `build.gradle`,
`gradle-unresolved-dependencies.txt` (requested coordinates, context and failure
reason), `gradle-output.txt`, and `gradle-resolution-report.json`. The JSON report
records status, timestamps, target fingerprint, configured and executed Gradle
versions, total wall-clock time, project generation, Gradle process and parsing
times. It also contains the combined configuration and each individual
dependency's resolution/graph traversal time, in execution order. The separate
`gradle-configuration-timings.json` contains the Gradle task measurements.
`gradleOutsideTaskMs` measures process time outside the measured task, including
startup, configuration and shutdown.

Times are milliseconds and exclude queue wait, OSV scanning and temporary
project cleanup. Each execution currently uses a fresh `GRADLE_USER_HOME`, and
later configurations may benefit from downloads made by the combined resolution.
Failed executions retain available artifacts and a failure report. An empty
unresolved file means the task found no unresolved dependencies; a placeholder
indicates the task never started. Cached resolution reuse does not generate a
new report: the files still describe the latest actual execution. Old run
directories remain on disk for the lifetime of the pod, but the browser exposes
only the latest run.
