package no.nav.platforce.tool.workflows

import no.nav.platforce.tool.node.NodeSecurityResult
import no.nav.platforce.tool.node.NodeSecurityScan
import no.nav.platforce.tool.node.NodeSecurityStatus
import no.nav.platforce.tool.node.PackageJsonParser
import no.nav.platforce.tool.node.enrichNodeRepositorySecurity
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.RepositoryDependencyInventory
import no.nav.platforce.tool.repositories.RepositoryProjectType
import no.nav.platforce.tool.security.OverrideReason
import no.nav.platforce.tool.security.TransientDependencyUsage
import no.nav.platforce.tool.targets.TargetVersionsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeRepositorySecurityTest {
    private val scanner = DependencyScanner(RecordingGithubClient())
    private val state =
        TargetVersionsState(
            mutableMapOf(),
            mutableMapOf(),
            "8.0",
            nodeDependencies = mutableMapOf("bull" to "4.16.5", "uuid" to "11.1.1"),
            nodeTransientDependencies = setOf("uuid"),
        )
    private val consumer =
        NodeSecurityResult(
            "bull",
            "4.16.5",
            NodeSecurityStatus.OK_OVERRIDDEN,
            relatedTo = listOf(OverrideReason("uuid", "11.1.1", "11.1.1", "8.3.2", listOf("uuid:11.1.1"))),
        )
    private val cover =
        NodeSecurityResult(
            "uuid",
            "11.1.1",
            NodeSecurityStatus.OK_TRANSIENT,
            transientUsage = listOf(TransientDependencyUsage("bull", "4.16.5")),
        )

    private fun scan(
        manifest: String,
        security: List<NodeSecurityResult> = listOf(consumer, cover),
        targets: TargetVersionsState = state,
    ) = enrichNodeRepositorySecurity(
        scanner.compare(
            RepositoryDependencyInventory(
                "example/node",
                "2026-11-17",
                emptyMap(),
                emptyMap(),
                null,
                projectType = RepositoryProjectType.NODE,
                nodeDependencies = PackageJsonParser().parse(manifest),
            ),
            targets,
        ),
        NodeSecurityScan(security),
        targets,
    )

    @Test
    fun `matching dependency and global override expose shared statuses and detail references`() {
        val result =
            scan("""{"dependencies":{"bull":"^4.16.5"},"overrides":{"uuid":"~11.1.1"}}""")
        assertEquals(listOf(DependencyStatus.OK_OVERRIDDEN, DependencyStatus.OK_TRANSIENT), result.findings.map { it.status })
        assertEquals(
            "uuid",
            result.findings[0]
                .relatedTo
                .single()
                .key,
        )
        assertEquals(
            "bull",
            result.findings[1]
                .relatedTo
                .single()
                .key,
        )
        assertTrue(result.findings.flatMap { it.relatedTo }.all { it.kind == DependencyKind.NODE })
        assertEquals("^4.16.5", result.findings[0].currentVersion)
    }

    @Test
    fun `normal dependency and scoped override do not stand in for a missing global override`() {
        listOf(
            """{"dependencies":{"bull":"4.16.5","uuid":"11.1.1"}}""",
            """{"dependencies":{"bull":"4.16.5"},"overrides":{"another":{"uuid":"11.1.1"}}}""",
            """{"dependencies":{"bull":"4.16.5"},"overrides":{"uuid@^8":"11.1.1"}}""",
        ).forEach { manifest ->
            val result = scan(manifest)
            assertEquals(DependencyStatus.OK_WITH_ADD, result.findings.first().status, manifest)
            val addition = result.findings.single { it.status == DependencyStatus.ADD }
            assertEquals("uuid", addition.key)
            assertEquals("overrides > uuid", addition.declaration)
            assertEquals("bull", addition.relatedTo.single().key)
        }
    }

    @Test
    fun `direct uuid can coexist with the cover without receiving the bandage role`() {
        val result =
            scan("""{"dependencies":{"bull":"4.16.5","uuid":"11.1.1"},"overrides":{"uuid":{".":"11.1.1"}}}""")
        assertEquals(
            listOf(DependencyStatus.OK_OVERRIDDEN, DependencyStatus.OK, DependencyStatus.OK_TRANSIENT),
            result.findings.map { it.status },
        )
    }

    @Test
    fun `version changes retain comparison priority and unsupported specifications stay unresolved`() {
        val manifest = """{"dependencies":{"bull":"4.16.4"},"overrides":{"uuid":"12.0.0"}}"""
        val results = listOf(consumer.copy(status = NodeSecurityStatus.VULNERABLE), cover)
        assertEquals(listOf(DependencyStatus.UPDATE, DependencyStatus.AHEAD), scan(manifest, results).findings.map { it.status })
        assertEquals(
            DependencyStatus.UNRESOLVED,
            scan("""{"dependencies":{"bull":">=4.16.5"}}""", results).findings.single().status,
        )
        mapOf(
            NodeSecurityStatus.VULNERABLE to DependencyStatus.VULNERABLE,
            NodeSecurityStatus.UNRESOLVED to DependencyStatus.UNRESOLVED,
            NodeSecurityStatus.UNSUPPORTED to DependencyStatus.UNRESOLVED,
            NodeSecurityStatus.OK to DependencyStatus.OK,
        ).forEach { (targetStatus, repositoryStatus) ->
            val target = consumer.copy(status = targetStatus, relatedTo = emptyList())
            assertEquals(repositoryStatus, scan("""{"dependencies":{"bull":"^4.16.5"}}""", listOf(target)).findings.single().status)
        }
    }

    @Test
    fun `redundant covers are removable but ordinary and scoped declarations are not`() {
        val unused = cover.copy(status = NodeSecurityStatus.TRANSIENT_UNUSED, transientUsage = emptyList())
        val result =
            scan(
                """{"dependencies":{"uuid":"11.1.1"},"overrides":{"uuid":"10.0.0","another":{"uuid":"11.1.1"}}}""",
                listOf(unused),
            )
        assertEquals(
            listOf(DependencyStatus.OK, DependencyStatus.REMOVE, DependencyStatus.OK_TRANSIENT),
            result.findings.map { it.status },
        )
    }

    @Test
    fun `shared missing covers produce one ADD with all consumers`() {
        val targets = state.copy(nodeDependencies = (state.nodeDependencies + ("other" to "1.0.0")).toMutableMap())
        val other = consumer.copy(key = "other", targetVersion = "1.0.0")
        val result =
            scan("""{"dependencies":{"bull":"4.16.5","other":"1.0.0"}}""", listOf(consumer, other, cover), targets)
        assertEquals(2, result.findings.count { it.status == DependencyStatus.OK_WITH_ADD })
        assertEquals(
            setOf("bull", "other"),
            result.findings
                .single { it.status == DependencyStatus.ADD }
                .relatedTo
                .map { it.key }
                .toSet(),
        )
        val upgrade = scan("""{"dependencies":{"bull":"^4.16.4"}}""")
        assertEquals(listOf(DependencyStatus.UPDATE, DependencyStatus.ADD), upgrade.findings.map { it.status })
    }

    @Test
    fun `every attributed provider is required and unknown coverage does not become green`() {
        val targets =
            state.copy(
                nodeDependencies = (state.nodeDependencies + ("second" to "2.0.0")).toMutableMap(),
                nodeTransientDependencies = setOf("uuid", "second"),
            )
        val cooperative =
            consumer.copy(relatedTo = consumer.relatedTo.map { it.copy(causedBy = listOf("uuid:11.1.1", "second:2.0.0")) })
        val second = cover.copy(key = "second", targetVersion = "2.0.0")
        val manifest = """{"dependencies":{"bull":"4.16.5"},"overrides":{"uuid":"11.1.1"}}"""
        val result = scan(manifest, listOf(cooperative, cover, second), targets)
        assertEquals(DependencyStatus.OK_WITH_ADD, result.findings.first().status)
        assertEquals("second", result.findings.single { it.status == DependencyStatus.ADD }.key)

        val unknown = consumer.copy(relatedTo = consumer.relatedTo + OverrideReason("unknown", "1.0.0", "2.0.0"))
        assertEquals(DependencyStatus.UNRESOLVED, scan(manifest, listOf(unknown, cover)).findings.first().status)
        assertEquals(
            DependencyStatus.UNRESOLVED,
            scan(manifest, listOf(consumer, cover.copy(targetVersion = "10.0.0"))).findings.first().status,
        )
    }
}
