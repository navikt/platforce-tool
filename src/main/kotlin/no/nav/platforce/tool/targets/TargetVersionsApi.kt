package no.nav.platforce.tool.targets

import com.google.gson.Gson
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.security.PluginSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind

fun targetVersionsRoutes(
    resolutionScanner: GradleTargetResolutionScanner,
    securityScanner: TargetSecurityScanner,
    pluginSecurityScanner: PluginSecurityScanner? = null,
    upgradeSecurityScanner: UpgradeSecurityScanner? = null,
) = listOf(
    "/internal/api/target-versions" bind Method.GET to { request ->
        val context = request.userContext()
        Response(Status.OK)
            .header("Content-Type", "application/json")
            .body(Gson().toJson(context.targetVersionsStore.get(includeIgnored = true)))
    },
    "/internal/api/target-versions/update" bind Method.POST to { request ->
        val context = request.userContext()
        val body = request.bodyString()
        val parsed = Gson().fromJson(body, TargetVersionsState::class.java).snapshot()

        TargetVersionsUpdater(resolutionScanner, securityScanner, pluginSecurityScanner, upgradeSecurityScanner).save(context, parsed)

        Response(Status.OK).body("updated")
    },
)
