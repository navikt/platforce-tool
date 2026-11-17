package no.nav.platforce.tool.targets

import com.google.gson.Gson
import no.nav.platforce.tool.node.nodeTargetVersion
import java.security.MessageDigest

fun fingerprint(targetState: TargetVersionsState): String {
    val canonical =
        Gson().toJson(
            listOf(
                targetState.gradleVersion,
                targetState.plugins.toSortedMap(),
                targetState.dependencies.toSortedMap(),
                targetState.transientDependencies.sorted(),
                targetState.platformBoms.toSortedMap(),
                targetState.ignoredDependencies.sorted(),
                targetState.nodeDependencies.mapValues { nodeTargetVersion(it.value) }.toSortedMap(),
            ),
        )
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
