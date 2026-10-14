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
