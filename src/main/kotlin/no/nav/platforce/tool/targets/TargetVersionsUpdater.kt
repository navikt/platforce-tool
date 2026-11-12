package no.nav.platforce.tool.targets

import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.security.PluginSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.user.UserContext

class TargetVersionsUpdater(
    private val resolutionScanner: GradleTargetResolutionScanner,
    private val securityScanner: TargetSecurityScanner,
    private val pluginSecurityScanner: PluginSecurityScanner? = null,
    private val upgradeSecurityScanner: UpgradeSecurityScanner? = null,
) {
    fun save(
        context: UserContext,
        state: TargetVersionsState,
        expected: TargetVersionsState? = null,
    ) {
        context.targetVersionsStore.update(state, expected)
        securityScanner.invalidate(context.username)
        resolutionScanner.invalidate(context.username)
        pluginSecurityScanner?.invalidate(context.username)
        upgradeSecurityScanner?.invalidate(context.username)
    }
}
