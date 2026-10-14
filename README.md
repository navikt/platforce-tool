# platforce-tool [<img align="right" src="https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png" width="18" alt="GitHub repository" />](https://github.com/navikt/platforce-tool)
Selfservice console for operating on Salesforce middleware apps

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
