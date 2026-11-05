package no.nav.platforce.tool.workflows

import no.nav.platforce.tool.pullrequests.GradleBuildFileUpdater
import no.nav.platforce.tool.repositories.DependencyFinding
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.RepositoryDependencyScan
import no.nav.platforce.tool.security.OverrideReason
import no.nav.platforce.tool.security.TargetSecurityResult
import no.nav.platforce.tool.security.TargetSecurityScan
import no.nav.platforce.tool.security.TargetSecurityStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SharedOverrideAdditionTest {
    @Test
    fun `shared Guava override appears once with every dependent grpc module and is added once to the build`() {
        val parents = listOf("io.grpc:grpc-auth", "io.grpc:grpc-core")
        val guava = "com.google.guava:guava"
        val findings =
            parents.map {
                DependencyFinding(DependencyKind.DEPENDENCY, it, "1.83.1", "1.85.1", DependencyStatus.UPDATE)
            }
        val security =
            TargetSecurityScan(
                emptyList(),
                parents.map {
                    TargetSecurityResult(
                        it,
                        "1.85.1",
                        TargetSecurityStatus.OK_OVERRIDDEN,
                        emptyList(),
                        emptyList(),
                        listOf(OverrideReason(guava, "33.8.0-jre", "33.8.0-jre", "33.4.0-jre")),
                    )
                },
            )
        val scanner = DependencyScanner(RecordingGithubClient())
        val scan = scanner.enrichSecurityScan(RepositoryDependencyScan("example/repository", "", findings), security)
        val addition = scan.findings.single { it.status == DependencyStatus.ADD }
        assertEquals(guava, addition.key)
        assertEquals(parents, addition.relatedTo.map { it.key })
        assertEquals(listOf("1.85.1", "1.85.1"), addition.relatedTo.map { it.version })
        assertEquals(2, scan.findings.count { it.status == DependencyStatus.UPDATE })
        val source = "dependencies {\n" + parents.joinToString("\n") { "    implementation '$it:1.83.1'" } + "\n}"
        val updated = GradleBuildFileUpdater().apply(source, scan.findings)
        assertEquals(1, Regex("implementation \"com.google.guava:guava:33.8.0-jre\"").findAll(updated).count())
    }
}
