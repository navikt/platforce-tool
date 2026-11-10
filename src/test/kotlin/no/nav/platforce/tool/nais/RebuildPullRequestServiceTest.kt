package no.nav.platforce.tool.nais

import no.nav.platforce.tool.workflows.RecordingGithubClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.time.LocalDate

class RebuildPullRequestServiceTest {
    private val normal = "name: Deploy\non:\n  push:\n  pull_request:\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
    private val mono =
        """
        on:
          workflow_call:
            inputs:
              DEPLOY_APP:
                required: true
                type: string
              DEPLOY_CLUSTER:
                required: true
                type: string
              FLAG_SEEK:
                required: true
                type: boolean
              NUMBER_OF_SAMPLES:
                required: true
                type: string
        jobs:
          build:
            steps:
              - uses: actions/upload-artifact@pinned
                with:
                  name: imageUrl
        """.trimIndent()

    @Test
    fun `normal workflow gains workflow_call without changing push or pull request configuration`() {
        val plan = rebuildWorkflowPlan(normal, false, "main")
        assertEquals(normal.replace("on:\n", "on:\n  workflow_call:\n"), plan.called)
        assertTrue(plan.wrapper.contains("uses: ./.github/workflows/deploy.yml"))
        assertTrue(plan.wrapper.contains("refs/heads/main"))
        assertFalse(plan.wrapper.contains("targets:"))
        assertEquals(plan.called, rebuildWorkflowPlan(plan.called, false, "main").called)
        Yaml().compose(plan.wrapper.reader())
    }

    @Test
    fun `monorepo wrapper exposes only targets and supplies known defaults including zero samples`() {
        val plan = rebuildWorkflowPlan(mono, true, "trunk")
        assertTrue(plan.wrapper.contains("NUMBER_OF_SAMPLES: '0'"))
        assertTrue(plan.wrapper.contains("FLAG_SEEK: false"))
        assertTrue(plan.wrapper.contains("uses: ./.github/workflows/workflow.yml"))
        assertTrue(plan.wrapper.contains("refs/heads/trunk"))
        assertTrue(plan.called.contains("|| 'imageUrl'"))
        assertTrue(plan.called.contains("inputs.DEPLOY_CLUSTER"))
        assertEquals(plan.called, rebuildWorkflowPlan(plan.called, true, "trunk").called)
        Yaml().compose(plan.called.reader())
        Yaml().compose(plan.wrapper.reader())
    }

    @Test
    fun `unsupported workflows fail before any repository writes`() {
        listOf(
            "on: [push]" to false,
            normal to true,
            mono.replace("FLAG_SEEK", "UNKNOWN_REQUIRED") to true,
            mono.replace("type: string", "type: boolean") to true,
        ).forEach { (source, isMono) ->
            assertThrows(IllegalArgumentException::class.java) { rebuildWorkflowPlan(source, isMono, "main") }
        }
    }

    @Test
    fun `PR adds missing file and updates called workflow but never overwrites existing rebuild file`() {
        val github = RecordingGithubClient(mapOf(".github/workflows/deploy.yml" to normal))
        val url = RebuildPullRequestService(github).create("example", "repo")
        assertTrue(url.endsWith("/pull/1"))
        assertEquals(setOf(".github/workflows/rebuild.yml", ".github/workflows/deploy.yml"), github.updatedFiles.keys)
        assertTrue(github.calls.any { it.startsWith("createBranch:") })
        assertTrue(github.calls.any { it == "resolveBranchName:example/repo" })
        assertTrue(github.pullRequestTitle!!.endsWith(LocalDate.now().toString()))
        assertTrue(github.requestedBranchName!!.endsWith(LocalDate.now().toString()))
        val existing = RecordingGithubClient(mapOf(".github/workflows/rebuild.yml" to "existing"))
        assertThrows(IllegalArgumentException::class.java) { RebuildPullRequestService(existing).create("example", "repo") }
        assertTrue(existing.updatedFiles.isEmpty())
    }
}
